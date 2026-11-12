# Issue #38: simplified JSON archive loader

## Current implementation

The DAG call is:

```python
load_daily_summary_staging = GarminDailySummaryLoadOperator(
    task_id="load_daily_summary_staging",
    json_file=ingest_daily_summary.output_json_file,
    processing_date=PROCESSING_DATE,
    postgres_conn_id="garmin_analytics_loader",
)
```

The file stays sourced from ingestion's named file XCom reference. Both
`json_file` and `processing_date` remain templated. Task IDs, setup/ingestion
upstream dependencies, Connection IDs, and the existing directory XCom remain
unchanged. The loader has no internal XCom pull or filename construction.

Neither loader class nor the DAG configures an archive root. The shared reader
requires an absolute JSON file path and an empty `_SUCCESS` marker in that file's
parent directory. File and marker symlinks must still remain within that resolved
parent. There is no global filesystem-root restriction. Files are never rewritten.

`JsonArchiveLoadOperator` accepts `sql_resource` explicitly in its constructor and
keeps archive reading and SQL execution. `GarminDailySummaryLoadOperator.__init__`
passes `sql_resource="sql/daily_summary_upsert.sql"` directly to `super().__init__`;
there is no subclass resource class attribute. The subclass retains profile-ID,
calendar-date, and SQL-parameter validation/preparation.

Both SQL files name the fixed `staging.stg_garmin_daily_summary` table. Dynamic
schema/table arguments, identifier formatting, the DAG's identifier filter, and
`sql_identifiers.py` were removed. The existing table definition is unchanged.
Profile ID, date, and original JSON text remain three separate query parameters.
The full JSONB payload, nested Body Battery data, conflict replacement, and one
transaction with autocommit disabled are preserved. Payloads remain excluded
from SQL parameter logging and XCom output.

## Unit and static verification

Verified on 2026-11-12 with the existing Airflow 3.3.1 image as an isolated Python
3.13 unit/static-check environment:

- **124 unit tests passed** across ingestion, archive/loader behavior, and DAG
  configuration/SQL handoff checks.
- Ruff passed across both operator projects, authentication code, DAGs, and
  Airflow tests.
- Python syntax, project TOML, and `git diff --check` passed.

Archive fixtures are temporary files outside the former fixed archive root.
Tests cover absolute paths, parent completion markers, missing files/markers,
malformed UTF-8/JSON, non-object payloads, file/marker symlinks, unchanged bytes,
profile/date validation before connection, raw JSON parameter binding, packaged
SQL loading/failure, and transaction settings. The generic operator is tested
with an explicitly supplied SQL resource, while subclass tests verify its
constructor-selected resource and absence of the removed configuration fields.

DAG tests inspect and resolve the ingestion file XComArg using a mocked task
instance, including ingestion attempt 4 with loader try 99. They verify the
requested constructor wiring, unchanged dependencies, fixed setup SQL, and no
identifier filter. PostgreSQL/Kubernetes calls are mocked. Existing opt-in
PostgreSQL fixtures were adapted to remove root configuration but were not run.
Existing Kubernetes provider deprecation warnings remain.

No functional, integration, live DAG, PostgreSQL-write, or end-to-end tests were
run. No image build or deployment was performed. Live task/Connection resolution,
archive access in the scheduler, and database writes remain for manual
verification after review and an Airflow image rebuild/load.

## Reproduce

Use the constrained environment from CONTRIBUTING.md with the Garmin Python
packages and `garmin-airflow-operators[dev]` installed:

```bash
python -m pytest -q --tb=short --show-capture=no -p no:cacheprovider \
  airflow/tests/test_daily_summary_loader.py \
  airflow/tests/test_garmin_daily_pipeline.py \
  garmin-airflow-operators/tests \
  garmin-integration/garmin-data-ingestion/python/tests
ruff check airflow/dags airflow/tests garmin-airflow-operators \
  garmin-integration/garmin-auth/python \
  garmin-integration/garmin-data-ingestion/python
git diff --check
```

## Pending files

This list includes retained, uncommitted file-handoff changes from the preceding
review and the current simplification. The identifier helper and its tests are
deleted:

- `CONTRIBUTING.md`
- `README.md`
- `airflow/dags/garmin_daily_pipeline.py`
- `airflow/dags/sql/ensure_daily_summary_staging.sql`
- `airflow/tests/test_daily_summary_loader.py`
- `airflow/tests/test_daily_summary_staging_postgresql.py`
- `airflow/tests/test_garmin_daily_pipeline.py`
- `docs/architecture.md`
- `docs/postgresql.md`
- `docs/verification/0038-daily-summary-loader.md`
- `garmin-airflow-operators/README.md`
- `garmin-airflow-operators/src/garmin_airflow_operators/daily_summary_load_operator.py`
- `garmin-airflow-operators/src/garmin_airflow_operators/json_archive_load_operator.py`
- `garmin-airflow-operators/src/garmin_airflow_operators/sql/daily_summary_upsert.sql`
- `garmin-airflow-operators/src/garmin_airflow_operators/sql_identifiers.py`
- `garmin-airflow-operators/tests/conftest.py`
- `garmin-airflow-operators/tests/test_daily_summary_load_operator.py`
- `garmin-airflow-operators/tests/test_json_archive_load_operator.py`
- `garmin-airflow-operators/tests/test_sql_identifiers.py`
- `garmin-integration/garmin-data-ingestion/python/src/garmin_data_ingestion/operator.py`
- `garmin-integration/garmin-data-ingestion/python/tests/test_ingestion_operator.py`

All work remains unstaged and uncommitted for manual review. No Git staging,
commits, pushes, or PR creation were performed.
