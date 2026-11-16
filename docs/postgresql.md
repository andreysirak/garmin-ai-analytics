# Local analytics PostgreSQL

`garmin_analytics` runs independently of Airflow metadata. The daily DAG creates
its staging schema (#37) and loads successful raw archives (#38). Both databases
use separate host-backed volumes that survive kind node recreation (#58).

## Setup and account configuration

Start with the existing template:

```bash
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
```

Fill in the local values once, using **existing** passwords and the original
Fernet key for an existing encrypted cluster. For a fresh local setup, leave
`"fernet_key": ""`; no key generation is required and Airflow stores connection
passwords unencrypted in metadata. With storage/kind prepared, deploy with:

```bash
make postgresql-up
make infra-up
```

`postgresql-up` reads only the three analytics passwords and works without a
Garmin API key. It validates configuration/live Secrets before Kubernetes changes,
deploys the pinned chart, authenticates existing application logins before role
changes, applies `roles.sql`, and initializes only unset passwords noninteractively.
`infra-up` uses the same file for Airflow's PostgreSQL credentials/Fernet key and
passwords for the two managed Garmin connection definitions in
`config/connections.example.json`. No normal-setup password prompts or manually
copied `config/connections.json` passwords remain. See the
[complete credential mapping and adoption instructions](credentials.md).

| Account | Purpose | Local password entry |
| --- | --- | --- |
| `garmin_analytics` | Bootstrap superuser; trusted setup/backup/restore | `postgresql.garmin_analytics_password` |
| `garmin_owner` | NOLOGIN owner; holds schemas and application objects | None |
| `garmin_schema_manager` | Dedicated schema setup login; can SET ROLE to owner | `postgresql.garmin_schema_manager_password` |
| `garmin_loader` | Staging SELECT/INSERT/UPDATE/DELETE and sequence access | `postgresql.garmin_loader_password` |

Airflow's separate PostgreSQL instance has its own `postgres` administrator and
`airflow` metadata login; both passwords are included in the same template.
Analytics uses `POSTGRES_USER=garmin_analytics`, with
`auth.enablePostgresUser: false`, so no additional analytics `postgres` password
is configured. Existing passwords are never automatically reset to fit the file.
Secret conflicts identify the local entry without printing values; API errors stop
setup instead of being treated as missing Secrets.

Roles/grants remain in `infrastructure/postgresql/roles.sql`. `make postgresql-roles`
reapplies them and checks/initializes application passwords from the local file.
Existing password-bearing roles must authenticate through the Service before any
role/grant changes. Native `psql` handles client-side password encryption from
stdin with no TTY and captured output. Plaintext passwords are not interpolated
into SQL source, command arguments, or server statements.

The loader is not an owner/admin and cannot create/drop application objects.
The schema login must use `SET LOCAL ROLE garmin_owner` inside its transaction.
PUBLIC database/schema privileges are revoked; new staging objects inherit loader
grants only when created as `garmin_owner`. The daily DAG uses separate
`garmin_analytics_schema` and `garmin_analytics_loader` definitions from the
checked-in example, resolved with these exact passwords. The old `garmin_analytics_migrations` Connection is not
removed automatically; retain it until any older consumers have been reviewed.

## Deployment and connections

| Resource | Value |
| --- | --- |
| Namespace | `garmin-analytics` |
| Helm release / StatefulSet / Service | `garmin-postgresql` |
| Chart | `oci://registry-1.docker.io/bitnamicharts/postgresql`, version `18.12.18` |
| Image | Docker Official Image `postgres:17.11-bookworm` |
| Database / bootstrap login | `garmin_analytics` |
| Internal endpoint | `garmin-postgresql.garmin-analytics.svc.cluster.local:5432` |
| Mac endpoint | `127.0.0.1:5432`, kind NodePort `30432` |
| Bootstrap Secret | `garmin-postgresql`, key `password` |
| PVC / static PV | `garmin-postgresql-data`, 5 GiB, ReadWriteOnce, Retain |
| Host data | `data/postgresql/garmin-analytics/pgdata` |

The chart values adapt the official image to UID/GID 999, its data directory,
`POSTGRES_DB`, a writable root filesystem, and readiness without Bitnami's
initialization marker. The fixed PostgreSQL 17 image/major is unchanged.
`global.security.allowInsecureImages=true` permits this non-Bitnami image; it does
not disable TLS certificate verification. Bitnami-specific replication, metrics,
backup/password-update jobs, and volume permission containers remain disabled.
Storage guard init containers verify marker, major, and write access first.

The external PVC and password Secret are outside the Helm release. Rollback or
uninstall never deletes their host files. Static capacities are requests rather
than enforced filesystem quotas. See [migration and rebuild](database-storage.md)
before deploying this configuration over older node-local PVCs.

Preflight also checks the fixed loopback port mapping and native `psql` client
before Kubernetes writes. Install `libpq` on macOS (`brew install libpq`); Make
finds unlinked Homebrew installations automatically. The loopback mapping is
created with kind and cannot be added to an existing node in place.

```bash
make postgresql-status
make airflow-connections-import  # Re-derive managed connections and import optional custom entries
```

The override file `config/connections.json` is optional, mode 0600, and remains
ignored. It can override nonsecret fields or add unrelated connections. Both
Garmin definitions default to `config/connections.example.json`, with their
passwords supplied only by `credentials.local.json`. Inline managed passwords
are rejected. Import uses Airflow's connection model directly, without generated
password files, and preserves existing unrelated connections. Existing managed
passwords must match the local file. Encrypted installations must retain the
original Fernet key to decrypt them; fresh local installations use an empty setting.
Use `AIRFLOW_CONNECTIONS_FILE` to select a different custom file; use
`CREDENTIALS_FILE` to select a different private setup file.

The manual, unscheduled `garmin_analytics_connection_check` DAG uses PostgresHook
and Connection ID `garmin_analytics_loader` for `SELECT 1`; no passwords are stored
in DAG source. For a deliberately interactive Mac SQL session:

```bash
psql -h 127.0.0.1 -p 5432 -U garmin_loader -d garmin_analytics -W
```

Interactive `postgresql-loader-password` and `postgresql-schema-manager-password`
commands remain **explicit rotation tools**, not setup steps. Coordinate any
rotation with the local file and live Connections; deployment will never rotate
an existing password automatically. `make postgresql-password` is an explicit
password-display tool, not invoked by deployment or normal validation.

### Existing clusters: back up before recreation

For the database-mount migration and subsequent reattachment, follow
[database storage](database-storage.md). The manual dumps below remain useful
for the optional backup/restore work tracked in #35; save the protected credential
file (`config/credentials.local.json`) with its original Fernet key as well. Do not
automatically invoke the old dump-restore sequence on an already migrated database.

kind/Docker cannot add host-port bindings to an existing node container.
`make postgresql-up` fails **before applying resources** when the mapping is
missing or is not bound to `127.0.0.1`. It never deletes/recreates a cluster,
changes a PVC, or starts a temporary port-forward to work around this check.

Keep the existing cluster running while preparing backups. Example manual
commands from the repository root (run in a shell with `pipefail` enabled):

```bash
umask 077
backup_dir="data/backups/pre-port-mapping-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  exec statefulset/garmin-postgresql -- sh -ec \
  'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec pg_dump -Fc -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  > "$backup_dir/garmin_analytics.dump"
kubectl --context kind-garmin-ai-analytics -n airflow get secret airflow-postgresql \
  -o jsonpath='{.data.password}' | base64 -d | \
  kubectl --context kind-garmin-ai-analytics -n airflow exec -i airflow-postgresql-0 -- \
  sh -ec 'export PGPASSWORD="$(cat)"; exec pg_dump -Fc -h 127.0.0.1 -U airflow -d airflow' \
  > "$backup_dir/airflow.dump"
pg_restore --list "$backup_dir/garmin_analytics.dump" > /dev/null
pg_restore --list "$backup_dir/airflow.dump" > /dev/null
```

Also preserve host archives, any custom role definitions/passwords, and other
PVC contents you need (for example Airflow logs). Backups include credentials and
private data: keep them local, protected, and outside the cluster. Check command
exit codes and test restore; a nonempty file alone does not prove a usable backup.

**Only after checking all required backups**, manually recreate with the updated
`infrastructure/kind/cluster.yaml` (`make cluster-delete`, then
`make cluster-create`). Recreate namespaces and create missing Secrets from the
private setup file before starting PostgreSQL/Airflow, so encrypted connections and bootstrap
passwords remain valid. Restore database contents as the bootstrap admin, not as
`garmin_loader`. For analytics objects restore with ownership assigned to
`garmin_owner` (for example `pg_restore --no-owner --role=garmin_owner` after role
setup), reapply `make postgresql-roles`, and retain the configured role passwords.
Restore Airflow metadata before resuming its DAGs. Run `make postgresql-up`,
run `make infra-up` to derive both application connections, and verify clients after recovery.

## Roll back a deployment

```bash
make postgresql-status                      # Includes Helm revision history
make postgresql-rollback                    # Previous Helm revision
make postgresql-rollback POSTGRESQL_REVISION=N  # Select a Bitnami revision from history
```

Rollback restores the Helm-managed StatefulSet and other release resources and waits for readiness.
It reuses the same PVC and Secret. A first installation has no previous revision;
rollback then fails without deleting storage. Helm rollback creates a new revision
in release history. To reapply the checked-out chart after a rollback, run
`make postgresql-up` again.

Rollback does **not** undo SQL writes, schema changes, password changes, or restore
a database snapshot. It does not affect Airflow metadata, Garmin credentials, or
raw JSON archives. PostgreSQL major-version upgrades/downgrades require a separate
data migration/restore plan; changing a container image and rolling back Helm is
not a database-version migration mechanism.

## Verify persistence

```bash
make postgresql-up
make infra-up
make postgresql-verify
```

Run verification on the local development database with no concurrent deployment
operations. It briefly interrupts database connections. The script:

1. Checks role grants, Mac psql access, and Airflow's internal loader connection.
2. Checks that the database Service rejects passwordless connections, then uses
   the mounted password to create a uniquely named synthetic table and commit a row.
3. Deletes the PostgreSQL Pod, waits for a different Ready Pod, reads the row,
   and rechecks both Mac psql and Airflow connections.
4. Creates a Helm revision with a harmless Pod annotation, waits for its rollout,
   and reads the row again.
5. Calls `make postgresql-rollback` to restore the starting revision, waits for a
   replacement Pod, reads the row, and rechecks both clients.
6. Confirms the PVC UID is unchanged after each step and drops the synthetic table.

The check keeps the image and credentials unchanged and requires no Garmin data
or API calls. It leaves Helm revision history, but restores the starting deployment
configuration. If verification fails after the upgrade, it attempts rollback and
table cleanup; inspect the release and rerun cleanup if cluster access is lost.

### Switching from the earlier custom chart

For a local deployment created by the earlier custom chart in this issue, stop
its Deployment before upgrading so two controllers never start servers against
the same PVC:

```bash
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  scale deployment/garmin-postgresql --replicas=0
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  wait --for=delete pod -l app.kubernetes.io/name=garmin-postgresql --timeout=120s
make postgresql-up
make postgresql-verify
```

The upgrade replaces the old Deployment with the StatefulSet, while keeping the
same Secret, PVC, image version, and PostgreSQL data directory. New installations
only need `make postgresql-up`. Rollback verification stays within Bitnami chart
revisions; do not roll back to the earlier custom chart while the StatefulSet is
running, because that transition requires stopping the active controller first.

Offline bootstrap checks and manifest validation:

```bash
python3 -m unittest discover -s infrastructure/tests -v
make infra-validate
```

See the [recorded local verification](verification/0036-postgresql.md) for results.

References: [Bitnami PostgreSQL chart](https://github.com/bitnami/charts/tree/main/bitnami/postgresql),
[PostgreSQL official image initialization and volume behavior](https://hub.docker.com/_/postgres),
[Helm rollback](https://helm.sh/docs/helm/helm_rollback/), and
[Kubernetes persistent volume lifecycle](https://kubernetes.io/docs/concepts/storage/persistent-volumes/).


## Daily-summary staging contract (#37)

`garmin_daily_pipeline.ensure_daily_summary_staging` runs independently of Garmin
authentication and ingestion. Its `SQLExecuteQueryOperator` uses only Airflow
Connection `garmin_analytics_schema` (login `garmin_schema_manager`). The versioned
SQL runs as one transaction with autocommit disabled and assumes `garmin_owner`
locally, preserving the existing owner/default-grant setup and separate loader
Connection `garmin_analytics_loader`.

The task creates `garmin_analytics.staging.stg_garmin_daily_summary` when missing:

| Column | Type | Required meaning |
| --- | --- | --- |
| `user_profile_id` | bigint NOT NULL | Garmin profile ID |
| `calendar_date` | date NOT NULL | Summary's calendar date |
| `payload` | jsonb NOT NULL | Complete parsed Garmin daily-summary response object |

The nondeferrable primary key is **(user_profile_id, calendar_date)**: one complete
summary per user and date. `CHECK (jsonb_typeof(payload) = 'object')` rejects arrays,
scalars, and JSON null; NOT NULL also rejects SQL NULL. An empty object is allowed
by this structural check. Metric completeness, value ranges, and agreement
between the business key and payload fields belong to downstream quality checks.
No metric columns, event tables, or timestamp columns are created here.

### Raw archive, loader, and typed views

The original Garmin response remains unchanged in the raw archive, including
whitespace and original timestamp/numeric representations. JSONB holds the full
**parsed response**, not a byte-for-byte copy. It retains nested objects, lists,
nulls, unknown response fields, and all Body Battery feedback/activity events
inside `payload`; the loader must not project only a known subset of fields.
Timestamp strings, whether offsetless or containing `Z`/another offset, stay
strings in the payload without timezone conversion.

`load_daily_summary_staging` (#38) configures `GarminDailySummaryLoadOperator` from
`garmin_airflow_operators.daily_summary_load_operator` in the standalone
`garmin-airflow-operators/` project. It inherits archive validation and database
execution from `JsonArchiveLoadOperator`. The DAG supplies
`json_file=ingest_daily_summary.output_json_file`, the templated processing date,
and PostgreSQL Connection ID. The daily-summary subclass passes
`sql_resource="sql/daily_summary_upsert.sql"` explicitly to the base constructor;
it does not define a resource class attribute. Setup and loading target the
fixed `staging.stg_garmin_daily_summary` table in their SQL files. There are no
schema/table constructor arguments, dynamic SQL identifiers, or identifier filters.
After successful ingestion, the producer publishes the exact full JSON path under
`OUTPUT_JSON_FILE_XCOM_KEY` (`output_json_file`) and retains the legacy directory
XCom. Airflow resolves the named file reference before loader execution. The
loader opens that path directly without an internal `xcom_pull`, filename
construction, or latest-attempt search. `json_file` and `processing_date` are
template fields; the latter verifies the payload date independently of its filename.
The file path must be absolute and comes directly from ingestion; no archive
root is configured in either loader class. The file's parent
must contain an empty `_SUCCESS` marker, and JSON/marker symlinks must resolve
within that parent.
It validates an object with a positive integer `userProfileId` that fits bigint
and a valid `calendarDate` in `YYYY-MM-DD` form matching the DAG's processing date.
Missing files, invalid JSON/keys, and date mismatches fail before database access.
The task stores that key and the complete object in one transaction using
parameterized SQL with autocommit disabled through `garmin_analytics_loader`.
Only the key and payload are stored in PostgreSQL; original response bytes stay
in the raw archive. `ON CONFLICT (user_profile_id, calendar_date) DO UPDATE`
replaces the full payload, including Garmin corrections, while keeping earlier
raw files available for replay. The load task depends on **both**
`ensure_daily_summary_staging` and `ingest_daily_summary` with `all_success`.

The current LocalExecutor runs Python tasks in the scheduler. Its Helm values
mount the existing kind node directory `/mnt/garmin-raw-data` read-only at
`/data/raw/garmin`; Makefile passes the archive's host GID as a scheduler
supplemental group. Apply the updated Helm values using `make infra-up` before
running the loader on an existing deployment. Direct Helm users must also pass
`--set 'scheduler.securityContexts.pod.supplementalGroups[0]=<archive-host-gid>'`.
No new PVC, database schema, or credential change is required by the loader.

Typed SQL views will later extract only metrics needed for analysis from the
JSONB object. Timestamp interpretation and explicit GMT/local timezone rules
belong to those typed projections, with tests independent of the session timezone.
Nested events remain in the payload until an actual query needs another model.
Typed views, sleep loading, and downstream row-quality tasks remain separate steps.

### Exact versioned schema-contract verification

An empty temporary contract table provides a single definition for creation and
verification; it disappears on commit or rollback. Verification compares exactly
three column names, types/precision, nullability, defaults, and identity/generated
properties. It also compares constraint definitions (including CHECK validation
state) and requires one valid, immediate primary unique index. Constraint names
and physical column order may differ, and extra nonunique indexes are allowed.
Additional columns, constraints, or unique indexes require a reviewed contract
change, even if an extra nullable column would allow some loader writes.

Reruns preserve existing schema and rows. This task reads PostgreSQL metadata,
not stored health data, and never repairs, truncates, or replaces an existing
table. Errors include the expected and actual contract and a migration hint.
The `ACCESS SHARE` lock blocks operations requiring `ACCESS EXCLUSIVE` until
transaction completion; compatible concurrent DDL is still possible, so schema
changes must be coordinated with daily runs.

### Existing tables

An existing table with a different definition fails verification with
`Schema migration required ... expected three-column JSONB contract`.
Schema setup never drops columns or changes existing rows automatically. Apply
any required schema change separately before rerunning the task, or use a fresh
database. Original archived responses remain available for replay.

The Connection-name upgrade instructions above still apply independently: keep
`garmin_schema_manager` and the separate loader login. Their connection settings
are now derived from `credentials.local.json`; adopt existing passwords and keep
the original Fernet key when encryption is enabled. See [credential setup](credentials.md).

### Verification

The pinned Airflow image includes the PostgreSQL/common-SQL providers; Python CI
installs the PostgreSQL provider using Airflow constraints. For local Python tests,
include `apache-airflow-providers-postgres` in the constrained installation from
CONTRIBUTING.md. Opt-in integration tests require `GARMIN_STAGING_TEST_DSN` pointing
to an **empty disposable** `garmin_analytics` database with a bootstrap-admin test
login. They install roles, execute the real DAG operator/PostgresHook as
`garmin_schema_manager`, and remove their synthetic staging schema afterward.
Never point them at a database containing real data. See the
[verification results](verification/0037-daily-summary-staging.md).
Loader-specific checks and deployment limits are recorded in
[issue #38 verification](verification/0038-daily-summary-loader.md).
