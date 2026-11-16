# PostgreSQL storage across kind recreation (#58)

Both databases use separate Mac directories passed through Colima into kind.
PVCs are explicitly bound to static `Retain` PVs with `hostPath.type: Directory`.
There is no fallback to dynamically provisioned node-local database storage.

| Database | Mac directory, relative to the repository | kind mount | Container data directory | Major / UID |
| --- | --- | --- | --- | --- |
| Airflow metadata | `data/postgresql/airflow` | `/mnt/postgresql-airflow` | `/bitnami/postgresql/data` | 16 / 1001 |
| Garmin analytics | `data/postgresql/garmin-analytics` | `/mnt/postgresql-garmin-analytics` | `/var/lib/postgresql/data/pgdata` | 17 / 999 |

The existing pinned charts/images are retained: Airflow chart 1.22.0 supplies
PostgreSQL 16.1.0; analytics uses PostgreSQL 17.11. Reattachment is not a major
version upgrade. Confirm the actual running versions before migrating an existing
installation with local overrides. Database files, markers, the private setup
file, and backups stay outside version control and Docker build contexts.

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
directories. Docker probes check marker identity and write access as UID 1001
and 999 with the host supplemental group. No database is initialized by a probe.

Cluster creation checks host visibility first, then inspects both actual kind
bind mounts and reads their markers inside the node. Reusing an older cluster
without these mounts fails with recreation instructions; nothing is deleted.
Deployments repeat these checks before Kubernetes changes. Init containers then
check the expected marker, existing `PG_VERSION`, and write access to the actual
PVC before either PostgreSQL server starts. Helm rendering requires a supplied
identity; `infra-validate` uses a rendering-only value and does not inspect data.

Missing directories/markers, an incorrect mount source, read-only access, wrong
database identity, incompatible major, or inaccessible database files must be
fixed explicitly. Do not work around a failure by regenerating a marker or
pointing a populated database at an empty directory.

## Preserve credentials with data

Use the single mode-0600 `config/credentials.local.json` file described in
[credential setup and existing-cluster adoption](credentials.md). It contains all
five PostgreSQL passwords, the original Airflow Fernet key, and Garmin API key.
The deployment validates required fields and existing Secrets before applying
changes. It creates absent Secrets through stdin and never overwrites conflicting
values. Analytics roles and managed Airflow connections reuse these same values.
No separate Secret snapshot files are generated or restored.

Before rebuilding an existing cluster, populate this file with the credentials
already in use and verify both `make analytics-credentials-check` and
`make airflow-credentials-check`. Preserve it with the database backups. Losing
its original Fernet key prevents decryption of existing Airflow values.

## One-time migration from node-local PVCs

Existing PVC storage classes/bindings and kind mounts cannot be changed in place.
Do not delete a live PVC or point the new chart at blank storage before making
and checking backups. This migration requires a maintenance window.

1. Pause DAGs, let active ingestion/loading finish, and stop other writers.
   Record the running PostgreSQL majors/images, expected Airflow run history,
   analytics row counts, and date coverage locally without publishing private data.
2. Adopt the existing values in `config/credentials.local.json`, including the
   original Fernet key. Preserve optional custom connections, raw archives,
   and other needed volumes (Airflow logs remain node-local).
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
   settings. Verify historical DAG runs, encrypted Connections, and analytics
   row counts/date coverage before resuming scheduling. Subsequent rebuilds
   reattach these populated directories and do not need dump imports.

## Explicit rebuild after migration (#55)

`stack-up` and `stack-restart` from
[#55](https://github.com/andreysirak/garmin-ai-analytics/issues/55) are still pending.
Their explicit rebuild path should use this sequence and must not automatically
delete a cluster or initialize replacement storage when checks fail:

1. Pause scheduling, finish active work, and save optional logical backups (#35).
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
   data files; no dumps are imported. Verify history, analytics counts and encrypted values
   before unpausing DAGs.

Host-backed persistence is not a backup: disk loss, manual deletion, SQL mistakes,
or a lost Fernet key still require recovery. Static capacities are requests, not
filesystem quotas. `Retain` preserves host files, but deleting/recreating a PVC
within an existing cluster can leave its PV Released and require deliberate
rebinding; never delete the directory to fix a binding.

## Verification

See [recorded focused credential validation](verification/0058-credentials.md).

Focused offline checks are `python3 -m unittest discover -s infrastructure/tests -v`
and `make infra-validate`. They use synthetic configuration or manifest rendering;
neither deploys to the live cluster. Credential file modes, per-target validation,
Secret conflicts, safe stdin transport, role-password initialization/preservation,
and generated/custom connection handling have focused tests.

Full recreation acceptance remains separate: use an isolated disposable kind
cluster, synthetic credentials and rows, and separate host directories/ports.
Never bind a second server to the live directories. Check both database histories
survive deletion/recreation without dump imports and that the original Fernet key
still decrypts a Connection. Omit/swap a mount, remove a marker, provide the wrong
identity/major, or revoke write access; preflight or the init container must fail
before PostgreSQL starts. Record actual executed results rather than treating
this checklist as completed verification.
