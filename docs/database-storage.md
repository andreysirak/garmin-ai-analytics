# PostgreSQL storage across kind recreation (#58)

Both databases use separate Mac directories passed through Colima into kind.
PVCs are explicitly bound to static `Retain` PVs with `hostPath.type: Directory`.
There is no fallback to dynamically provisioned node-local database storage.

| Database | Mac directory, relative to the repository | kind mount | Container data directory | Major |
| --- | --- | --- | --- | --- |
| Airflow metadata | `data/postgresql/airflow` | `/mnt/postgresql-airflow` | `/bitnami/postgresql/data` | 16 |
| Garmin analytics | `data/postgresql/garmin-analytics` | `/mnt/postgresql-garmin-analytics` | `/var/lib/postgresql/data/pgdata` | 17 |

The existing pinned charts/images are retained: Airflow chart 1.22.0 supplies
PostgreSQL 16.1.0; analytics uses PostgreSQL 17.11. Reattachment is not a major
version upgrade. Confirm the actual running versions before migrating an existing
installation with local overrides. Database files, markers, the private setup
file, and backups stay outside version control and Docker build contexts.

Make runs both PostgreSQL processes and their storage-check init containers as
the current host user's UID (`id -u`). The image's group and the supplemental
host group remain available. PostgreSQL requires its data directory to be owned
by the process UID; group write access alone is insufficient. This matters with
Colima SSHFS, which can present created files as the Mac user (for example UID
501), regardless of the image's default UID (999 or 1001). Run Make as the same
non-root user across deployment and rebuilds.

## First installation

Colima must expose the repository at the same absolute path with write access.
Its default home-directory mount is writable. If you use custom mounts, include
the repository (or its parent) in your Colima configuration, for example:

```yaml
mounts:
  - location: /absolute/path/to/garmin-ai-analytics
    writable: true
```

Keep the repository path stable across rebuilds. Do not switch Docker contexts
or Colima profiles while reattaching databases. Never delete the Colima VM or host
directories as part of a kind rebuild. See the official
[Colima mount configuration](https://colima.run/docs/configuration/#volume-mounts)
and [kind extra mounts](https://kind.sigs.k8s.io/docs/user/configuration/#extra-mounts).

From the repository root, for a **new installation with no database to migrate**:

```bash
make prepare-database-storage
make cluster-create
make postgresql-up
make infra-up
```

Preparation explicitly creates two volume roots and different `.volume-id`
markers. It changes only root/marker permissions and assigns the host group;
it never recursively changes database files or adopts unidentified nonempty
directories. Docker probes check marker identity, write access, and the ownership
of a temporary directory as the host UID, with the host supplemental group. They
also check the owner of an existing PostgreSQL data directory. Probes never
initialize a database or change the owner of existing files.

Cluster creation checks host visibility first, then inspects both actual kind
bind mounts and reads their markers inside the node. Reusing an older cluster
without these mounts fails with recreation instructions; nothing is deleted.
Deployments repeat these checks before Kubernetes changes. Init containers then
check the expected marker, directory owner, existing `PG_VERSION`, and write access to the actual
PVC before either PostgreSQL server starts. Helm rendering requires a supplied
identity; `infra-validate` uses a rendering-only value and does not inspect data.

Missing directories/markers, an incorrect mount source, read-only access, wrong
database identity, incompatible major, or inaccessible database files must be
fixed explicitly. Do not work around a failure by regenerating a marker or
pointing a populated database at an empty directory.

If startup reports `data directory ... has wrong ownership`, compare the directory
owner with the Pod's configured PostgreSQL UID. The local Make targets supply the
host UID to both Helm charts and the checks above. Apply the corrected configuration
with `make stack-up` (or `make postgresql-up` followed by `make infra-up`); changing
the process UID does not require recreating kind or deleting data. If existing
files have a different owner, stop and review their migration rather than running
a recursive `chown` or reinitializing the database.

## Preserve credentials with data

Use the single mode-0600 `config/credentials.local.json` file described in
[credential setup and existing-cluster adoption](credentials.md). It contains all
five PostgreSQL passwords, the Airflow Fernet setting, and Garmin API key. Fresh
local installations leave `"fernet_key": ""` and store Airflow connection values
unencrypted in metadata; no key generation is required. Existing encrypted
installations must keep their original key.
The deployment validates required fields and existing Secrets before applying
changes. It creates absent Secrets through stdin and never overwrites conflicting
values. Analytics roles and managed Airflow connections reuse these same values.
No separate Secret snapshot files are generated or restored.

Before rebuilding an existing cluster, populate this file with the credentials
already in use and verify both `make analytics-credentials-check` and
`make airflow-credentials-check`. Preserve it with the database backups. If
encryption is enabled, losing its original Fernet key prevents decryption of
existing Airflow values.

## One-time migration from node-local PVCs

Existing PVC storage classes/bindings and kind mounts cannot be changed in place.
Do not delete a live PVC or point the new chart at blank storage before making
and checking backups. This migration requires a maintenance window.

1. Pause DAGs, let active ingestion/loading finish, and stop other writers.
   Record the running PostgreSQL majors/images, expected Airflow run history,
   analytics row counts, and date coverage locally without publishing private data.
2. Adopt the existing values in `config/credentials.local.json`, including the
   original Fernet key if encryption is enabled. Preserve optional custom
   connections, raw archives, and other needed volumes (Airflow logs remain node-local).
3. Use the manual dump commands in
   [the existing backup procedure](postgresql.md#existing-clusters-back-up-before-recreation)
   to create separate custom-format dumps for `airflow` and `garmin_analytics`.
   Also preserve custom role definitions/passwords using a protected globals
   backup or your existing role/password configuration. Check `pg_restore --list`
   and perform a test restore before deleting the original cluster.
   The optional `db-backup`/`db-restore` convenience targets in
   [#35](https://github.com/andreysirak/garmin-ai-analytics/issues/35) are still
   pending; this change does not claim to implement them.
4. Only after those backups are checked, explicitly delete the old cluster with
   `make cluster-delete`, prepare the new directories, and run `make cluster-create`.
5. Recreate namespaces/resources and create the original Secrets from the local
   file before starting either database:

   ```bash
   kubectl --context kind-garmin-ai-analytics apply -k infrastructure/kubernetes/overlays/local
   kubectl --context kind-garmin-ai-analytics apply -k infrastructure/kubernetes/postgresql
   KUBE_CONTEXT=kind-garmin-ai-analytics bash infrastructure/scripts/bootstrap.sh
   make postgresql-up
   ```

6. Restore analytics as the trusted bootstrap admin, using the existing ownership
   contract (`garmin_owner`) and preserving/restoring application role passwords.
   Reapply `make postgresql-roles` and verify the loader/schema-manager credentials
   against the unchanged single-file configuration.
7. Start only the Airflow database while restoring metadata. The following
   temporary Helm invocation keeps Airflow components and schema migration stopped:

   ```bash
   storage_id=$(python3 infrastructure/scripts/local_database_storage.py identity --database airflow)
   helm --kube-context kind-garmin-ai-analytics upgrade --install airflow airflow \
     --repo https://airflow.apache.org --version 1.22.0 --namespace airflow \
     -f infrastructure/helm/airflow/values.yaml \
     --set-string garminRawDataGid="$(id -g)" \
     --set "postgresql.primary.podSecurityContext.runAsUser=$(id -u)" \
     --set "postgresql.primary.containerSecurityContext.runAsUser=$(id -u)" \
     --set "postgresql.primary.podSecurityContext.supplementalGroups[0]=$(id -g)" \
     --set-string postgresql.storageIdentity="$storage_id" \
     --set scheduler.replicas=0 --set apiServer.replicas=0 --set dagProcessor.replicas=0 \
     --set migrateDatabaseJob.enabled=false --wait --timeout 10m
   kubectl --context kind-garmin-ai-analytics -n airflow exec -i airflow-postgresql-0 -- \
     sh -ec 'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_restore -h 127.0.0.1 -U airflow -d airflow --clean --if-exists --exit-on-error' \
     < "$backup_dir/airflow.dump"
   ```

   Use the actual private backup directory from step 3. This command matches the
   pinned chart's password environment; do not echo it. Preserve the original
   Airflow version and Fernet key during this restore.
8. Run `make infra-up` to restore the checked-in component counts/migration
   settings. Verify historical DAG runs, Connections (including encrypted values
   when enabled), and analytics row counts/date coverage before resuming scheduling. Subsequent rebuilds
   reattach these populated directories and do not need dump imports.

## Startup and restart (#55)

The individual startup commands above remain supported. After configuring the
single private credentials file, `make stack-up` runs them in order, with the
existing `infra-validate` checks before deployment. It creates a missing cluster
or reuses a compatible one, deploys analytics before Airflow, and waits for
readiness. Only a new installation with neither database directory present can
prepare missing storage automatically. Partial storage or missing identity
markers require explicit investigation.

`make stack-restart` requires an existing compatible cluster. It reuses
`stack-up` to apply checked-in configuration and rebuild/load the local images,
then restarts both PostgreSQL instances and the Airflow API server. The existing
`infra-up` flow already restarts the scheduler and DAG processor; restart waits
for all these workloads. Finish active work before running it because database
connections are interrupted. It does not trigger DAGs or delete data/credentials.

A read-only preflight compares the configured node image, single-control-plane
topology, bind mount sources/access modes, and extra host ports against kind's
actual Docker node. Additional kind creation options require extending the
preflight explicitly. Creation-time differences, failed inspection, missing
storage directories, or missing markers stop before any deployment or rollout.
The commands never delete/recreate a cluster, reset storage, or rotate credentials
as a fallback. Existing Secret/role/connection checks remain in the reused
deployment targets and reject conflicting credentials.

## Explicit rebuild after migration (#55)

Creation-time kind changes need a deliberate rebuild. For existing node-local
PVCs, complete the one-time backup/restore migration above instead. For an
installation already using the host-backed volumes, use this sequence:

1. Pause scheduling, finish active work, and save and verify logical backups using
   [the manual backup procedure](postgresql.md#existing-clusters-back-up-before-recreation).
   The convenience backup/restore targets in #35 remain pending.
   Confirm that the private setup file contains all original credentials/Fernet
   key and matches the live cluster before deletion.
2. Stop database clients and cleanly stop both PostgreSQL StatefulSets. For the
   current single-node stack, after stopping Airflow clients:

   ```bash
   kubectl --context kind-garmin-ai-analytics -n airflow scale statefulset airflow-postgresql --replicas=0
   kubectl --context kind-garmin-ai-analytics -n garmin-analytics scale statefulset garmin-postgresql --replicas=0
   kubectl --context kind-garmin-ai-analytics -n airflow wait --for=delete pod/airflow-postgresql-0 --timeout=120s
   kubectl --context kind-garmin-ai-analytics -n garmin-analytics wait --for=delete pod/garmin-postgresql-0 --timeout=120s
   ```

3. Manually run `make cluster-delete`, then `make cluster-create`. Keep both host
   directories, their markers, and `config/credentials.local.json` unchanged. There is
   no need to rerun preparation, and it is not a rebuild/reset command.
4. Run `make postgresql-up`, then `make infra-up`. Bootstrap recreates missing
   Secrets from that file before the servers start. PostgreSQL reopens its existing
   data files; no dumps are imported. Verify history, analytics counts and Connections
   before unpausing DAGs.

After step 2, you may also run `make cluster-delete` followed by `make stack-up`
instead of the individual creation/deployment commands in steps 3 and 4. This is
still an explicitly requested deletion after verified backups; `stack-restart`
does not perform it for you.

Host-backed persistence is not a backup: disk loss, manual deletion, SQL mistakes,
or a lost Fernet key still require recovery. Static capacities are requests, not
filesystem quotas. Airflow metadata backups contain plaintext connection values
when the local Fernet setting is empty. `Retain` preserves host files, but
deleting/recreating a PVC within an existing cluster can leave its PV Released and require deliberate
rebinding; never delete the directory to fix a binding.

## Verification

See [recorded focused credential validation](verification/0058-credentials.md)
and [startup/restart validation](verification/0055-stack-startup.md).
See also [host UID compatibility checks](verification/0058-storage-ownership.md).

Focused offline checks are `python3 -m unittest discover -s infrastructure/tests -v`
and `make infra-validate`. They use synthetic configuration or manifest rendering;
neither deploys to the live cluster. Credential file modes, per-target validation,
Secret conflicts, safe stdin transport, role-password initialization/preservation,
and generated/custom connection handling have focused tests.

Full recreation acceptance remains separate: use an isolated disposable kind
cluster, synthetic credentials and rows, and separate host directories/ports.
Never bind a second server to the live directories. Check both database histories
survive deletion/recreation without dump imports, connections remain readable with
the empty local setting, and an enabled installation's original Fernet key still
decrypts its Connections. Omit/swap a mount, remove a marker, provide the wrong
identity/major, or revoke write access; preflight or the init container must fail
before PostgreSQL starts. Record actual executed results rather than treating
this checklist as completed verification.
