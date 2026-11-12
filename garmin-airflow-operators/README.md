# Garmin Airflow operators

A standalone Python project for loading immutable JSON archives into PostgreSQL.
Ingestion remains in `garmin-integration/garmin-data-ingestion/python`.

The loader hierarchy is `BaseOperator` → `JsonArchiveLoadOperator` →
`GarminDailySummaryLoadOperator`. The generic operator validates the archive,
loads a subclass's packaged SQL, and executes one parameterized transaction.
The daily-summary subclass owns its archive root, business-key validation, and
`sql/daily_summary_upsert.sql` resource.

```python
from garmin_airflow_operators import GarminDailySummaryLoadOperator

load_daily_summary_staging = GarminDailySummaryLoadOperator(
    task_id="load_daily_summary_staging",
    source_task_id=ingest_daily_summary.task_id,
    processing_date=PROCESSING_DATE,
    json_filename=f"{PROCESSING_DATE}.json",
    postgres_conn_id="garmin_analytics_loader",
    schema=STAGING_SCHEMA,
    table=DAILY_SUMMARY_TABLE,
)
```

Both `processing_date` and `json_filename` are templated. The source task must
publish its completed archive directory under the current run's `output_dir`
XCom key. The filename must be relative to that directory. Resolved directories
stay within the subclass archive root; JSON and `_SUCCESS` paths stay within the
source directory, including symlinks. The success marker must be empty.

The reader requires a UTF-8 JSON object. Daily summaries additionally require a
positive bigint `userProfileId` and `calendarDate` matching the processing date.
The original JSON text is bound directly to JSONB, retaining all nested data,
numeric precision, and line endings. Archive files are never rewritten.
Loader XCom output and SQL parameter logging are disabled.

To extend the base class, define `archive_root`, `sql_resource`, and
`prepare_parameters(payload, raw_json)`. That method validates payload-specific
fields and returns bind parameters. SQL resources are relative to `sql_package`,
which defaults to `garmin_airflow_operators`; external subclasses can use their
own package. Include SQL files as package data. SQL resources use `{schema}` and
`{table}` placeholders. The base operator validates and double-quotes these names
before substitution, while payload values remain separate SQL bind parameters.

The DAG owns the destination: define `STAGING_SCHEMA` and `DAILY_SUMMARY_TABLE`
once, then pass them to the loader and the setup SQL operator
(`params={"schema": STAGING_SCHEMA, "table": DAILY_SUMMARY_TABLE}`). Setup SQL
uses the shared `sql_identifier` Jinja filter on `task.params`, so run-level
parameter overrides cannot redirect setup away from the loader destination.
Accepted identifiers are 1–63 ASCII letters, digits, or underscores, starting
with a letter or underscore. Mixed case and reserved words are safely quoted.
Qualified names, quotes, SQL syntax, and overlong names are rejected. The DAG
does not choose the loader SQL resource; that remains subclass configuration.

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
