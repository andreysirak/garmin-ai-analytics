# Garmin Airflow operators

A standalone Python project for loading immutable JSON archives into PostgreSQL.
Ingestion remains in `garmin-integration/garmin-data-ingestion/python`.

The loader hierarchy is `BaseOperator` → `JsonArchiveLoadOperator` →
`GarminDailySummaryLoadOperator`. The generic operator validates the archive,
loads a subclass's packaged SQL, and executes one parameterized transaction.
The daily-summary subclass owns business-key validation and explicitly passes
`sql/daily_summary_upsert.sql` to the base constructor.

```python
from garmin_airflow_operators import GarminDailySummaryLoadOperator

load_daily_summary_staging = GarminDailySummaryLoadOperator(
    task_id="load_daily_summary_staging",
    json_file=ingest_daily_summary.output_json_file,
    processing_date=PROCESSING_DATE,
    postgres_conn_id="garmin_analytics_loader",
)
```

Both `json_file` and `processing_date` are templated. Ingestion publishes the full
archived JSON path under `output_json_file` after success and exposes its named
XCom reference as `output_json_file`. Airflow resolves that reference for the
loader. The legacy `output_dir` XCom remains available to existing callers.
The loader takes an absolute file path directly; it does not pull XCom, search
attempts, or construct a filename from the processing date. There is no configured
archive root. Its parent must contain an empty `_SUCCESS` marker; file and marker
symlinks must stay within that parent directory. The DAG keeps the path sourced
from ingestion through the named XCom reference.

The reader requires a UTF-8 JSON object. Daily summaries additionally require a
positive bigint `userProfileId` and `calendarDate` matching the processing date.
The original JSON text is bound directly to JSONB, retaining all nested data,
numeric precision, and line endings. Archive files are never rewritten.
Loader XCom output and SQL parameter logging are disabled.

To extend the base class, pass `sql_resource` explicitly to its constructor and
implement `prepare_parameters(payload, raw_json)`. That method validates
payload-specific fields and returns bind parameters. SQL resources are relative
to `sql_package`, which defaults to `garmin_airflow_operators`; external subclasses
can use their own package. Include SQL files as package data.

The daily-summary UPSERT and staging setup SQL both target the fixed table
`staging.stg_garmin_daily_summary`. Schema/table names are not constructor inputs
and SQL is not formatted with identifiers. Profile ID, date, and the full JSON
text remain separate bind parameters. The DAG supplies only the task ID, file
reference, processing date, and PostgreSQL Connection ID to the daily loader.

Use the constrained environment in [CONTRIBUTING.md](../CONTRIBUTING.md), including
`pip install -e 'garmin-airflow-operators[dev]'`. Unit/static checks from repo root:

```bash
pytest -q garmin-airflow-operators/tests airflow/tests/test_daily_summary_loader.py
ruff check garmin-airflow-operators airflow/dags airflow/tests
```

Tests use temporary archives and mocked XCom/PostgresHook. The Airflow Dockerfile
copies the package, including its SQL directory, into the existing Python path.
Rebuild/load the Airflow image after changes; source-only DAG updates do not
install the new package in an existing deployment.
