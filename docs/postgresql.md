# Local analytics PostgreSQL

Issue [#36](https://github.com/andreysirak/garmin-ai-analytics/issues/36) deploys
`garmin_analytics` independently of the Airflow metadata database. Schema creation
is implemented by #37 in the daily DAG; loading archived summaries belongs to #38.

## Deploy and inspect

From the repository root, with the local kind cluster running:

```bash
make cluster-create       # Only needed if the cluster does not already exist
make postgresql-up
make postgresql-status
```

`postgresql-up` applies the namespace and PVC, bootstraps the password Secret, and
installs/upgrades the pinned Bitnami PostgreSQL chart. It does not build Garmin images, deploy
Airflow, or require Garmin credentials. Repeating the command preserves the
existing password and data.

Before any Kubernetes writes it checks the kind node's Docker port mapping and
the local `psql` executable. After deployment it applies the version-controlled
roles/grants as the bootstrap admin and checks password-authenticated access at
`127.0.0.1:5432`. Install the Mac client with `brew install libpq`. Preflight and
connection checks find it through `brew --prefix libpq` when `psql` is not on
`PATH`. For manual `psql` commands, add `$(brew --prefix libpq)/bin` to `PATH` or
use the full executable path.

| Resource | Value |
| --- | --- |
| Namespace | `garmin-analytics` |
| Helm release / StatefulSet / Service | `garmin-postgresql` |
| Helm chart | `oci://registry-1.docker.io/bitnamicharts/postgresql`, version `18.12.18` |
| Database / bootstrap user | `garmin_analytics` |
| Cluster endpoint | `garmin-postgresql.garmin-analytics.svc.cluster.local:5432` |
| Mac endpoint | `127.0.0.1:5432` → kind node port `30432` → PostgreSQL port `5432` |
| Password | Secret `garmin-postgresql`, key `password` |
| PersistentVolumeClaim | `garmin-postgresql-data`, 5 GiB, ReadWriteOnce |
| PostgreSQL data directory | `/var/lib/postgresql/data/pgdata` on the PVC |

The upstream chart manages a single-replica StatefulSet, Services, ServiceAccount,
NetworkPolicy, and PodDisruptionBudget. StatefulSet updates replace its one Pod
before starting a server against the same data directory. The headless Service
uses ClusterIP; `garmin-postgresql` is a NodePort Service on port
30432, published on the Mac only through a loopback-bound kind host mapping.
Airflow continues using `airflow-postgresql` in namespace `airflow`;
neither its database nor its release is changed by these targets.

### New kind cluster: complete setup order

1. Run `make cluster-create`. This creates the node's loopback port mapping;
   PostgreSQL itself is not running yet. An existing cluster is left unchanged.
2. Run `make postgresql-up`. It generates the bootstrap password if missing,
   deploys PostgreSQL, applies roles/grants, and verifies Mac access on port 5432.
3. Run `make postgresql-loader-password` and `make postgresql-schema-manager-password`
   to choose separate passwords for `garmin_loader` and `garmin_schema_manager`.
4. Create `config/connections.json` from `config/connections.example.json` if it
   does not already exist, set permissions to `0600`, and manually enter the same
   passwords. Keep the internal Service host, port 5432, database
   `garmin_analytics`, and dedicated logins from the template.
5. Run `make infra-up` to build/load workload images, deploy Airflow, wait for
   scheduler/DAG processor readiness, and import `config/connections.json` if it
   exists. Configure Garmin credentials separately for Garmin ingestion.
6. Run `make postgresql-verify` to check permissions, both clients, and persistence.

`config/connections.json` and `config/connections.local.json` are both excluded
from Git and Docker build contexts. If the connection file was newly added to
staging before the ignore rule was added, remove it from staging before entering
a password:

```bash
git restore --staged -- config/connections.json
git status --short -- config/connections.json
git check-ignore -v config/connections.json
```

For a newly added file, restore removes it from the index without deleting or
changing its local contents. The status command should then print nothing and
check-ignore should identify the ignore rule. A file already committed in HEAD
requires a separate untracking change; adding an ignore rule alone is not enough.

### Chart and image configuration

Only values are maintained in `infrastructure/helm/postgresql/values.yaml`;
there are no custom chart templates or vendored charts. The chart version is
pinned in the Makefile, and its bundled common dependency comes with the OCI
artifact. Rendering and deployment use the same chart version and values file.

The image is explicitly pinned to Docker Official Image
`docker.io/library/postgres:17.11-bookworm`. Using the Bitnami chart does not
require using its default container image. Keeping PostgreSQL 17 preserves the
existing database format and avoids a major-version migration or reliance on
archived `bitnamilegacy` images. The chart's own application-version metadata
does not determine the deployed PostgreSQL version when this override is set.

The values adapt the chart to this image: UID/GID 999, the existing data-directory
path, `POSTGRES_DB`, a writable root filesystem, and readiness without Bitnami's
`.initialized` marker. Startup and liveness use the chart's `pg_isready` probes.
`global.security.allowInsecureImages=true` bypasses the chart's Bitnami-image
allowlist so the official image can be used; TLS certificate verification is
not disabled. Replication, metrics, backup jobs, password-update jobs, and volume
permission init containers are not configured for this image. Enabling these
Bitnami-specific features requires a separate compatibility review.

The upstream chart's NOTES assume Bitnami container scripts and can report
authentication disabled when an external Secret is configured. Use the SQL
command below; `make postgresql-verify` explicitly checks that the Service
rejects passwordless connections before testing authenticated queries.

For an interactive local SQL session without printing the password:

```bash
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  exec -it statefulset/garmin-postgresql -- sh -ec \
  'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql -X -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

## Credentials and storage lifecycle

`infrastructure/scripts/bootstrap.sh --postgresql-only` follows the existing
bootstrap workflow: generate a random password once and preserve an existing
Kubernetes Secret. It streams the password through stdin without placing it in
command arguments, rendered Helm values, plaintext manifests, or normal output.
API lookup/creation failures stop deployment. This mode touches only the analytics
Secret and requires its namespace to exist; regular Airflow/Garmin bootstrap is
unchanged.

PostgreSQL reads the password from a read-only Secret mount. The configured user
`garmin_analytics` is the image's bootstrap superuser for setup and restore. It is
not an Airflow loader credential. The image's
database/user/password settings initialize **empty storage only**. Editing or
deleting the Secret does not rotate the password inside an existing database;
preserve it with the PVC and coordinate any rotation using SQL. Restore the
original Secret if it is lost. No credentials are committed to the repository.

To display the existing bootstrap admin password, just like `make airflow-password`:

```bash
make postgresql-password
```

The admin password is generated once by bootstrap during the first
`make postgresql-up` that reaches Secret creation. It already exists for a
previously installed database. This target only reads the existing Secret: it
does not generate a password, change it, deploy resources, or require Mac port
mapping. Use it for the bootstrap login `garmin_analytics`, not `garmin_loader`.

### Roles, ownership, and daily writes

`infrastructure/postgresql/roles.sql` is version-controlled and rerunnable. It
contains no passwords. `make postgresql-up` runs it automatically; to reapply it
after a restore, use `make postgresql-roles`.

| Role | Purpose and privileges |
| --- | --- |
| `garmin_analytics` | Bootstrap superuser; trusted setup, backups, and restore only |
| `garmin_owner` | NOLOGIN owner of the database and application schemas/objects |
| `garmin_loader` | LOGIN with CONNECT, staging schema USAGE, table SELECT/INSERT/UPDATE/DELETE, and sequence USAGE/SELECT |
| `garmin_schema_manager` | LOGIN with CONNECT and explicit SET ROLE permission for `garmin_owner`; schema setup only |

The loader is not a member of `garmin_owner`, cannot create schemas/tables or drop
owner tables, and has no administrative privileges. PUBLIC database privileges
and public-schema access are revoked. Future staging objects inherit loader
grants only when created **as `garmin_owner`**. During trusted setup use
`SET ROLE garmin_owner` before DDL and `RESET ROLE` afterward. Application table
definitions live in `airflow/dags/sql/ensure_daily_summary_staging.sql`; bootstrap
creates only the staging schema and grants.

Set the loader password separately, without putting it in SQL files or shell
arguments:

```bash
make postgresql-loader-password
```

This runs interactive psql `\password garmin_loader` as the bootstrap admin.
Choose and confirm the password, then put that same password into the local
Airflow connection file below. Reapplying roles does not reset passwords.
Unlike the bootstrap password, the loader password is not generated or saved in
the bootstrap Secret. You choose it with this interactive command; PostgreSQL
stores a verifier rather than a retrievable plaintext password. If you forget it,
set a new one and update/reimport the local connection file. `garmin_owner` is
NOLOGIN and has no password.

The daily DAG's `ensure_daily_summary_staging` SQL operator uses only Connection
ID `garmin_analytics_schema` (login `garmin_schema_manager`). Its transaction runs
`SET LOCAL ROLE garmin_owner`, so the table has the correct owner and inherits
loader DML grants. The schema-manager login does not inherit ownership automatically
and has no superuser, role-management, or database-creation privileges. Never
reuse the loader or bootstrap-admin connection for this task.

For an existing installation, run `make postgresql-roles`, then set the new
password interactively:

```bash
make postgresql-schema-manager-password
```

Add the `garmin_analytics_schema` entry from the example to the ignored local
connection file, fill in that password manually, and reimport it. Do not replace
an existing loader password. Existing loader-only files remain importable, but
schema setup requires the schema Connection to be configured separately.

### Upgrade an existing schema Connection

If schema setup was already configured, manually rename the JSON entry key
`garmin_analytics_migrations` to `garmin_analytics_schema` in the Git-ignored
`config/connections.json`. Keep its existing password and connection settings,
including login `garmin_schema_manager`, and leave `garmin_analytics_loader`
unchanged. This naming-only upgrade requires no password reset or database-role
changes; keep using `postgresql-schema-manager-password` for password changes.

Then rerun:

```bash
make airflow-connections-import
```

The import creates/updates `garmin_analytics_schema` in Airflow. It does not delete
the previously named Connection; that unused entry can be removed manually after
checking any other consumers. Local credential files and deployed Connections
must be updated by the owner, separately from the source change.

### Airflow connection and Mac access

```bash
cp config/connections.example.json config/connections.json
chmod 600 config/connections.json
```

Edit `config/connections.json` manually and replace
`SET_GARMIN_LOADER_PASSWORD_MANUALLY` and `SET_GARMIN_SCHEMA_MANAGER_PASSWORD_MANUALLY`
with the corresponding passwords you chose. The local file
is excluded from Git and Docker build contexts. Keep these template settings:

- Connection ID: `garmin_analytics_loader`
- Type: `postgres`; host: `garmin-postgresql.garmin-analytics.svc.cluster.local`
- Port: `5432`; schema/database: `garmin_analytics`; login: `garmin_loader`
- Schema-setup Connection: `garmin_analytics_schema`, same Service/port/database,
  login `garmin_schema_manager`

`make infra-up` automatically imports this file after Airflow is ready. If the
file is absent, it reports a skip and preserves existing Connections. If the file
exists but is invalid or still contains the password placeholder, import fails
and `infra-up` returns an error. A valid file overwrites the named Connection, so
local configuration is authoritative on subsequent deployments.

To reload changes without redeploying Airflow:

```bash
make airflow-connections-import
```

For an alternate file such as `config/connections.local.json`, override the path
for either deployment or manual import:

```bash
make infra-up AIRFLOW_CONNECTIONS_FILE=config/connections.local.json
make airflow-connections-import AIRFLOW_CONNECTIONS_FILE=config/connections.local.json
```

The importer accepts either known Connection independently or both together. It
rejects placeholders, unknown Connection IDs, an incorrect login (including the
bootstrap admin), or a Mac endpoint.
It streams the local JSON into a permission-restricted temporary file inside the
scheduler, invokes `airflow connections import --overwrite`, and removes the
temporary file. PostgreSQL deployment does not generate or rotate loader
passwords; Airflow deployment imports the password you configured. Airflow
protects stored passwords using its configured Fernet key, which must be
preserved with metadata backups.

The manual, unscheduled `garmin_analytics_connection_check` DAG uses
`PostgresHook(postgres_conn_id="garmin_analytics_loader")` and runs `SELECT 1`.
DAG source contains only the connection ID, with no host, username, or password.
It does not call Garmin or run migrations. Future daily loader tasks must use
the same connection-ID pattern. The pinned Airflow image includes PostgresHook.

From the Mac, use the same loader password interactively:

```bash
psql -h 127.0.0.1 -p 5432 -U garmin_loader -d garmin_analytics -W
```

No `kubectl port-forward` is needed. Airflow always uses the internal Service;
`127.0.0.1` inside an Airflow Pod would point back to that Pod.

The PVC is applied separately with Kustomize and is not a Helm release resource.
On kind it uses the default `standard` StorageClass, which stores data outside the
PostgreSQL container, on the kind node. Pod replacement, release upgrades,
rollback, and Helm uninstall leave the claim intact. `make infra-down` only
uninstalls Airflow and leaves this database running.

This is node-local development storage, not a backup. **Deleting the PVC or its
namespace, deleting the kind cluster (`make cluster-delete`), or losing the kind
node can destroy this database.** The default StorageClass has a `Delete` reclaim
policy. Unlike the raw JSON archive, this volume is not mounted from the Mac's
project directory. The 5 GiB claim is a storage request, not an enforced filesystem
quota on kind's local-path provisioner.

### Existing clusters: back up before recreation

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
for namespace in airflow garmin-analytics garmin-auth airflow-jobs; do
  kubectl --context kind-garmin-ai-analytics -n "$namespace" get secrets -o yaml \
    > "$backup_dir/$namespace-secrets.yaml"
done
pg_restore --list "$backup_dir/garmin_analytics.dump" > /dev/null
pg_restore --list "$backup_dir/airflow.dump" > /dev/null
```

Also preserve host archives, any custom role definitions/passwords, and other
PVC contents you need (for example Airflow logs). Backups include credentials and
private data: keep them local, protected, and outside the cluster. Check command
exit codes and test restore; a nonempty file alone does not prove a usable backup.

**Only after checking all required backups**, manually recreate with the updated
`infrastructure/kind/cluster.yaml` (`make cluster-delete`, then
`make cluster-create`). Recreate namespaces and restore the saved Secrets before
starting PostgreSQL/Airflow, so existing encrypted connections and bootstrap
passwords remain valid. Restore database contents as the bootstrap admin, not as
`garmin_loader`. For analytics objects restore with ownership assigned to
`garmin_owner` (for example `pg_restore --no-owner --role=garmin_owner` after role
setup), reapply `make postgresql-roles`, and set the loader password separately.
Restore Airflow metadata before resuming its DAGs. Run `make postgresql-up`,
import the loader connection if needed, and verify clients after recovery.

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
make postgresql-loader-password
make airflow-connections-import
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

Issue #38 will read a completed archive attempt (requiring `_SUCCESS`), obtain the
profile/date key, and store that key and the complete object in one transaction.
Only the key and payload are stored in PostgreSQL; original response bytes stay
in the raw archive. The intended upsert target remains
`ON CONFLICT (user_profile_id, calendar_date)`. Loading a corrected response should
replace the full payload while keeping earlier raw files available for replay.
The load task must depend on **both** `ensure_daily_summary_staging` and
`ingest_daily_summary`.

Typed SQL views will later extract only metrics needed for analysis from the
JSONB object. Timestamp interpretation and explicit GMT/local timezone rules
belong to those typed projections, with tests independent of the session timezone.
Nested events remain in the payload until an actual query needs another model.
Loader implementation, typed views, and downstream row-quality tasks are separate
steps and are not introduced by #37.

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
`garmin_schema_manager` and `postgresql-schema-manager-password`, and leave the
loader Connection separate. This refactor does not modify local credential files
or deployed Airflow Connections.

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
