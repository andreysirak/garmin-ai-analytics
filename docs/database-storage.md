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
installation with local overrides. Database files, markers, private Secret
snapshots, and backups stay outside version control and Docker build contexts.

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

Bootstrap saves private snapshots in `data/postgresql/credentials/`, with mode
0700 on the directory and 0600 on the JSON files. It saves the original database
Secrets, Airflow metadata connection, Fernet key, API/JWT keys, and UI password.
Secrets are restored through stdin before bootstrap can generate missing values;
their values are never printed by these helpers. Analytics role passwords remain
inside PostgreSQL and the ignored local Airflow connection configuration.

```bash
make db-secrets-export    # Explicitly refresh both snapshots from the current cluster
make db-secrets-restore   # Restore missing Secrets after both namespaces exist
```

Export requires both deployments' Secrets. It is available on the old cluster
before the new mounts exist. Restore refuses to overwrite differing live Secrets.
After intentionally rotating a Secret, verify it matches the database before
explicitly exporting again. Retained database files without a local snapshot or
their existing live Secrets cause bootstrap to stop instead of generating fresh
credentials. Keep an additional protected copy of snapshots with your backups;
losing the Fernet key makes previously encrypted Airflow values unreadable.

## One-time migration from node-local PVCs

Existing PVC storage classes/bindings and kind mounts cannot be changed in place.
Do not delete a live PVC or point the new chart at blank storage before making
and checking backups. This migration requires a maintenance window.

1. Pause DAGs, let active ingestion/loading finish, and stop other writers.
   Record the running PostgreSQL majors/images, expected Airflow run history,
   analytics row counts, and date coverage locally without publishing private data.
2. Run `make db-secrets-export`. Preserve `config/connections.json`, raw archives,
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
5. Recreate namespaces/resources and restore the original Secrets before starting
   either database:

   ```bash
   kubectl --context kind-garmin-ai-analytics apply -k infrastructure/kubernetes/overlays/local
   kubectl --context kind-garmin-ai-analytics apply -k infrastructure/kubernetes/postgresql
   make db-secrets-restore
   make postgresql-up
   ```

6. Restore analytics as the trusted bootstrap admin, using the existing ownership
   contract (`garmin_owner`) and preserving/restoring application role passwords.
   Reapply `make postgresql-roles` and verify the loader/schema-manager credentials
   against the unchanged local connection configuration.
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
   Export current Secrets with `make db-secrets-export`.
2. Stop database clients and cleanly stop both PostgreSQL StatefulSets. For the
   current single-node stack, after stopping Airflow clients:

   ```bash
   kubectl --context kind-garmin-ai-analytics -n airflow scale statefulset airflow-postgresql --replicas=0
   kubectl --context kind-garmin-ai-analytics -n garmin-analytics scale statefulset garmin-postgresql --replicas=0
   kubectl --context kind-garmin-ai-analytics -n airflow wait --for=delete pod/airflow-postgresql-0 --timeout=120s
   kubectl --context kind-garmin-ai-analytics -n garmin-analytics wait --for=delete pod/garmin-postgresql-0 --timeout=120s
   ```

3. Manually run `make cluster-delete`, then `make cluster-create`. Keep both host
   directories, their markers, and the credential snapshots unchanged. There is
   no need to rerun preparation, and it is not a rebuild/reset command.
4. Run `make postgresql-up`, then `make infra-up`. Bootstrap restores original
   Secrets before the servers start. PostgreSQL reopens its existing data files;
   no dumps are imported. Verify history, analytics counts and encrypted values
   before unpausing DAGs.

Host-backed persistence is not a backup: disk loss, manual deletion, SQL mistakes,
or a lost Fernet key still require recovery. Static capacities are requests, not
filesystem quotas. `Retain` preserves host files, but deleting/recreating a PVC
within an existing cluster can leave its PV Released and require deliberate
rebinding; never delete the directory to fix a binding.

## Verification status

Tests and infrastructure rendering have **not been run** for this change. No
live cluster, database, VM configuration, credentials, or data were changed.

When checks are authorized, run `python3 -m unittest discover -s infrastructure/tests -v`
and `make infra-validate`. Then use an isolated disposable kind cluster, synthetic
Secrets and rows, and separate host directories/ports; never bind a second server
to production data directories. Confirm both databases retain row/history values
after node deletion/recreation without importing dumps. Preserve the same Fernet
key and check an encrypted Connection. Check negative cases: omit/swap a mount,
remove a marker, supply the wrong identity/major, or revoke database write access.
Preflight must stop deployment, or the database Pod must remain in init failure
with its PostgreSQL container never started. Record actual results separately;
these acceptance scenarios are not yet verified.
