# Issue #37: full-response daily-summary staging

The current contract stores one complete parsed response per user/date using
`user_profile_id bigint NOT NULL`, `calendar_date date NOT NULL`,
`raw_file_path text NOT NULL`, and `payload jsonb NOT NULL`. It has primary key
`(user_profile_id, calendar_date)` and a validated payload-object CHECK.

The daily DAG task still uses `garmin_analytics_schema` as `garmin_schema_manager`,
assumes `garmin_owner`, and inherits the existing loader grants. No loader, typed
views, event tables, or deployment/credential updates are included.

## Verification method

Opt-in tests use disposable PostgreSQL 17.11 and the existing Airflow 3.3.1 image
on an isolated Docker network, without published host ports. Synthetic passwords
are generated at runtime and passed through a permission-restricted temporary env
file. Only Airflow's task-supervisor Connection lookup is replaced; the real DAG
operator and PostgresHook execute against PostgreSQL as `garmin_schema_manager`.
This is direct operator verification, not a scheduler/supervisor deployment test.

`GARMIN_STAGING_TEST_DSN` must point to an empty disposable `garmin_analytics`
database with a bootstrap-admin test login. The fixture installs application roles,
creates/removes only synthetic staging tables, and drops its staging schema when
finished. Never point it at the development database with real data.

The previous complete 95-column definition is retained only as the synthetic
`airflow/tests/sql/legacy_wide_daily_summary.sql` migration-preservation fixture.
It is not used by the DAG. The legacy test compares table OID/owner, all 95 column
names/types/nullability, constraints, index definitions, and all synthetic rows
before/after the rejected task execution.

## Commands

With the test DSN supplied through the protected environment and both existing
Garmin Python packages on PYTHONPATH:

```bash
python -m pytest -q --tb=short --show-capture=no -p no:cacheprovider \
  airflow/tests \
  garmin-integration/garmin-auth/python/tests \
  garmin-integration/garmin-data-ingestion/python/tests
```

Without a test DSN the PostgreSQL cases skip. No Garmin API calls or personal
responses are used. The raw-ingestion regression tests continue to cover
unchanged archival of response bytes.

See the [current contract and upgrade requirements](../postgresql.md#daily-summary-staging-contract-37).

## Results

Verified locally on 2026-11-11 against PostgreSQL 17.11, Airflow 3.3.1, and Python
3.13: **89 tests passed**, including **28 real-PostgreSQL integration cases**.
The four NOT NULL cases passed again after a lint-only context-manager cleanup.
Ruff and `git diff --check` passed. Existing Kubernetes provider
`reattach_on_restart` deprecation warnings remain.

| Check | Result |
| --- | --- |
| Fresh setup creates exactly four required columns, primary key, and payload-object CHECK | Passed |
| Table ownership and inherited loader DML grants | Passed |
| Two setup reruns preserve committed row, archive path, full payload, and schema | Passed |
| Nested Body Battery objects/events, unknown fields, nulls, and timestamp strings survive JSONB round-trip | Passed |
| Loader can upsert on the profile/date key and update the full payload/path | Passed |
| Arrays, strings, numbers, booleans, and JSON null are rejected by the object CHECK | Passed |
| SQL NULL in any contract column is rejected | Passed |
| Empty JSON object is accepted without metric-quality checks | Passed |
| Missing/wrong column types, nullability, defaults, or added columns fail with a migration message | Passed |
| Missing, incorrect, or deferrable key and missing/weaker/unvalidated payload CHECK fail | Passed |
| Extra unique index is rejected; equivalent renamed constraints are accepted | Passed |
| Existing complete 95-column table fails with an explicit wide-table migration hint | Passed; original schema, indexes, ownership, OID, and row preserved |
| A view cannot masquerade as the staging table | Passed |

Expected legacy-table error (schema details abbreviated):

```text
Schema migration required for staging.stg_garmin_daily_summary columns: expected four-column JSONB contract ..., found ...
HINT: Existing wide tables require a reviewed migration or replay from the raw archive. This task leaves existing schema and rows unchanged.
```

The disposable containers, network, and synthetic credentials were removed.
No development database/Connection, local credential file, or Git staging was
changed. No loader, typed view, or automatic wide-table migration was implemented.

## Files changed for the JSONB refactor

- `airflow/dags/sql/ensure_daily_summary_staging.sql`
- `airflow/tests/test_daily_summary_staging_postgresql.py`
- `airflow/tests/sql/legacy_wide_daily_summary.sql` (new synthetic fixture)
- `docs/postgresql.md`
- `docs/architecture.md`
- `docs/verification/0037-daily-summary-staging.md`
- `README.md`
- `CHANGELOG.md`

The existing DAG task, Connection IDs, database roles/permissions, and password
Make target remain unchanged by this refactor. Existing wide tables require a
separate reviewed migration/backfill using original archived responses.
