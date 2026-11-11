# Issue #36: PostgreSQL deployment and persistence

Verified locally on 2026-11-11 against the existing
`kind-garmin-ai-analytics` cluster, Kubernetes v1.33.7, Helm v4.3.1, Bitnami
PostgreSQL chart 18.12.18, and PostgreSQL 17.11
(`docker.io/library/postgres:17.11-bookworm`, arm64). No Garmin API calls or
personal data were used. Airflow was not redeployed. The OCI chart digest was
`sha256:5096e4ceed6e1a746be3abd50e4cd71392a7fc2b38e63d35504a905f99ae5e21`.

## Initial Bitnami chart verification

These initial results precede the role/connection/host-port follow-up below.

```bash
python3 -m unittest discover -s infrastructure/tests -v
make infra-validate
make postgresql-up
make postgresql-verify
```

- All four bootstrap tests passed: preserve existing credentials, generate a
  missing password without exposing it, stop on lookup errors, and report
  creation failures.
- Both Kustomize configurations rendered, and the pinned Bitnami and existing
  Airflow charts rendered successfully. The downloaded Bitnami chart also passed
  strict linting with the repository values.
- The initial custom Deployment was stopped before the Bitnami upgrade. A
  committed synthetic row survived the change to a StatefulSet (revision 5),
  with the same PVC UID and unchanged Secret UID/resourceVersion. The synthetic
  table was then removed. No custom chart templates remain in the repository.
- The persistence script printed:

```text
PASS: database Service requires a password
PASS: row and original PVC survive Pod replacement
PASS: row and original PVC survive deployment upgrade
PASS: row and original PVC survive deployment rollback
PASS: synthetic table removed; database deployment restored
```

The upgrade added only a test Pod annotation (revision 6). The script invoked
`make postgresql-rollback POSTGRESQL_REVISION=5`, producing revision 7, and
confirmed a different Ready Pod and the unchanged PVC UID after rollback.

An additional fresh-install check used an isolated, temporary namespace with an
empty PVC and synthetic password. The same chart and values initialized the
`garmin_analytics` database and user, and authenticated SQL through the Service
created and read a synthetic row. Helm uninstall preserved the external PVC and
Secret. The temporary namespace and its synthetic data were removed afterward.

The release is left running in `garmin-analytics`. This verifies Pod/deployment
persistence, not backup/restore or survival of kind cluster deletion. Schema and
loader behavior remain for #37 and #38. See [deployment and rollback scope](../postgresql.md).

## Roles, Airflow connection, and Mac loopback access

Verified on 2026-11-11 with the same chart/image versions and native Mac
`psql` 18.1. The existing `garmin-ai-analytics` kind cluster lacks port mapping
30432 → 127.0.0.1:5432. Running `make postgresql-up` against it failed at the
read-only preflight, before any apply, Secret creation, Helm upgrade, or SQL.
The existing cluster, database PVC, Airflow metadata, and Secrets were preserved.

End-to-end checks used a disposable `garmin-postgresql-verify` kind cluster and a
separate kubeconfig. It had the required loopback host mapping and retained the
same application namespace, Service, database, and role names. Only synthetic
credentials and data were used. The original cluster was not recreated.

- `make postgresql-up CLUSTER=garmin-postgresql-verify` created the NodePort
  Service, applied `roles.sql`, and passed native Mac password authentication.
- Reapplying `make postgresql-roles` succeeded without resetting passwords.
- A synthetic loader password was set separately from the version-controlled
  SQL. A temporary connection JSON based on the example was imported with
  `make airflow-connections-import AIRFLOW_CONNECTIONS_FILE=...` and removed.
- A separate Airflow 3.3.1 runner in namespace `airflow` used isolated SQLite
  metadata, not the real Airflow PostgreSQL metadata. It ran the actual manual
  `garmin_analytics_connection_check` DAG successfully via `airflow dags test`.
- `verify_roles.sql` created an owner-owned fixture inside a transaction, tested
  loader SELECT/INSERT/UPDATE/DELETE, and rejected table/schema creation,
  dropping the owner table, and assuming `garmin_owner`. Fixtures were rolled back.
- The complete `make postgresql-verify` passed: unchanged PVC and committed row
  across replacement, upgrade, and rollback; Mac psql and Airflow's internal
  PostgresHook connection passed before replacement, after replacement, and after
  rollback. Passwordless connections were rejected.
- Infrastructure rendering, all **8 infrastructure unit tests**, Ruff, and all
  **60 Python/DAG/operator/authentication tests** passed. Existing provider
  deprecation warnings remain unchanged.

Airflow 3's task SDK connection lookup needs a running task supervisor. The
standalone scheduler-side verification helper therefore resolves the imported
connection through Airflow's metadata/secrets model and supplies it to PostgresHook.
The actual DAG uses only `postgres_conn_id="garmin_analytics_loader"`.

The disposable cluster and temporary credential file are removed after the
checks. At that point, the real cluster required a user-controlled backup/recreation
before it could expose PostgreSQL on the new loopback mapping. No production
loader password or local connection file was filled in automatically. See the
[manual setup and recovery instructions](../postgresql.md).

## Homebrew client discovery and deployment after user recreation

On 2026-11-11 the user recreated `garmin-ai-analytics` with the required mapping.
Its next `make postgresql-up` stopped because Homebrew libpq was installed but
`psql` was absent from PATH. A shared client resolver now uses PATH first and
then `brew --prefix libpq`; both preflight and host verification use it.

All **12 infrastructure tests** and the targeted Ruff check passed. Retrying
`make postgresql-up` without modifying PATH succeeded on the new real cluster:
release revision 1 deployed, the 5 GiB PVC bound, role/grant SQL completed, and
native Mac `psql` authenticated to `127.0.0.1:5432`. The discovered executable was
`/opt/homebrew/opt/libpq/bin/psql` (18.1). No loader password was selected or
Airflow connection imported automatically; those remain explicit user steps.

## Automatic Airflow connection import

`make infra-up` now imports `config/connections.json` after the scheduler and
DAG processor rollouts complete. The updated target was executed successfully
against the recreated local cluster: its final output was
`Imported garmin_analytics_loader`. Post-deployment checks confirmed native Mac
psql authentication and Airflow PostgresHook connectivity through the internal
Service as `garmin_loader`.

All **16 infrastructure tests** and targeted Ruff checks passed. Regression
checks cover skipping an absent optional file without contacting Airflow,
rejecting missing files for explicit manual import, rejecting a present
placeholder file, and streaming a valid configured file to the importer.
`AIRFLOW_CONNECTIONS_FILE` can override the default path for either target.
