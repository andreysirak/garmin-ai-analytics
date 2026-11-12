# Issue #38: JSON archive loader refactoring

## Implementation

The standalone `garmin-airflow-operators/` Python project uses `pyproject.toml`
and a standard `src/garmin_airflow_operators` package. The hierarchy is:

```text
BaseOperator
    └── JsonArchiveLoadOperator
            └── GarminDailySummaryLoadOperator
```

`JsonArchiveLoadOperator` accepts `source_task_id`, `processing_date`,
`json_filename`, `postgres_conn_id`, `schema`, and `table`. Both date and filename
are templated; destination identifiers are explicit pipeline configuration.
It retrieves the source's `output_dir` XCom from the current run, requires a
completed archive, delegates payload validation/parameter preparation to the
subclass, reads its packaged SQL resource, and executes it through PostgresHook.
XCom publication and SQL parameter logging are disabled. Execute returns `None`.

The independent `read_json_archive` helper requires:

- An absolute existing directory resolving under the subclass archive root.
- A nonempty relative JSON filename, resolved inside that source directory.
- JSON and marker paths that remain inside the source directory after resolving
  traversal and symlinks, and an empty `_SUCCESS` marker.
- Valid UTF-8 and a JSON object, rejecting nonstandard numeric constants.

The helper returns the parsed object and original JSON text without modifying
archive bytes. Nested data, unknown fields, timestamps, line endings, and decimal
text remain in the bound JSON. No Python JSON reserialization is used.

`GarminDailySummaryLoadOperator` defines `/data/raw/garmin/daily-summary` and
`sql/daily_summary_upsert.sql` internally. Its parameter hook validates a positive
bigint `userProfileId` and a valid ISO `calendarDate` matching `processing_date`.
The SQL remains one parameterized upsert into the three-column JSONB table:
conflicts on `(user_profile_id, calendar_date)` replace the entire payload, with
`autocommit=False` preserving the existing transaction behavior.

The DAG supplies the templated filename, source task ID, processing date, and
existing `garmin_analytics_loader` Connection ID. `STAGING_SCHEMA` and
`DAILY_SUMMARY_TABLE` are defined once in the DAG and passed to the loader and
the setup SQL operator (via its `params`). It preserves task ID
`load_daily_summary_staging` and both `all_success` dependencies on setup and
summary ingestion. SQL resource selection stays inside the subclass; schema and
table selection belong to the DAG.
Ingestion's package, `output_dir` contract, Java behavior, and archive format
remain unchanged. No Sleep/Steps loaders or database migrations were added.

The Airflow Dockerfile copies the new package including its SQL directory.
Setuptools includes `sql/*.sql` as package data. Python CI installs, lints, and
unit-tests the new project alongside existing packages. Existing opt-in database
test fixtures were updated to reference the relocated class but were not run.

## Centralized destination configuration

`daily_summary_upsert.sql` uses `{schema}` and `{table}` placeholders. The base
loader validates and double-quotes each identifier before formatting SQL. Values
remain separate bind parameters; the raw JSON text and transaction settings are
unchanged. `ensure_daily_summary_staging.sql` uses the same quoting function as
an Airflow Jinja filter, including every target reference in DDL, regclass text,
and PL/pgSQL messages. Its `task.params` hold the DAG-configured destination,
preventing run-level params from redirecting setup away from the loader.

The supported identifier subset is 1–63 ASCII letters, digits, or underscores,
starting with a letter or underscore. Mixed case and reserved words are quoted;
qualified names, quotes, SQL syntax/delimiters, and overlong names are rejected.
Both existing Connection IDs, role handling, table definition, JSONB payload,
XCom behavior, and UPSERT/transaction semantics are preserved. No database schema
changes or Flyway implementation were introduced.

## Unit and static verification

Verified on 2026-11-12 with the existing Airflow 3.3.1 image as an isolated Python
3.13 environment:

- **163 unit tests passed**: generic archive reader/base class, daily-summary
  subclass, packaged SQL, mocked database calls, ingestion regressions, and DAG
  configuration/template checks, identifier validation, and setup SQL handoff.
- Ruff passed across the new project, DAGs/Airflow tests, and both existing Garmin
  Python packages.
- Python syntax and project TOML static checks passed.
- `git diff --check` passed.

All archive fixtures were temporary. Operator tests mocked XCom and PostgresHook;
DAG wiring/date tests inspect configuration and templates. Setup-operator unit
tests execute only its handoff through a mocked database hook.
Tests include containment and symlink escapes for directories/files, filename
traversal, missing/nonempty markers, malformed UTF-8/JSON, non-object JSON,
invalid/missing business keys, bigint bounds, and date mismatches. They check
unchanged archives, original text binding, no payload in logs/XCom, inherited
subclass behavior, SQL resource loading/failures, and rejection before connection.
Tests also verify custom schema/table names in both SQL paths, consistent DAG
configuration, unchanged value binds, quoted reserved words/mixed case, and
rejection of unsafe identifiers before database access. A packaging test verifies
SQL is declared as package data and accessible through
`importlib.resources`. No distribution build or image build was performed.

No functional, integration, live DAG, PostgreSQL-write, or end-to-end testing
was performed for this refactor. Earlier database results do not verify this
implementation. Existing Kubernetes provider deprecation warnings remain.

## Reproduce

Use the constrained environment in CONTRIBUTING.md, installing both existing
Garmin packages and `garmin-airflow-operators[dev]`:

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

## Files changed by destination centralization

- `airflow/dags/garmin_daily_pipeline.py`
- `airflow/dags/sql/ensure_daily_summary_staging.sql`
- `airflow/tests/test_daily_summary_loader.py`
- `airflow/tests/test_garmin_daily_pipeline.py`
- `airflow/tests/test_daily_summary_staging_postgresql.py` (fixture context only; not run)
- `garmin-airflow-operators/src/garmin_airflow_operators/json_archive_load_operator.py`
- `garmin-airflow-operators/src/garmin_airflow_operators/sql_identifiers.py`
- `garmin-airflow-operators/src/garmin_airflow_operators/sql/daily_summary_upsert.sql`
- `garmin-airflow-operators/tests/conftest.py`
- `garmin-airflow-operators/tests/test_daily_summary_load_operator.py`
- `garmin-airflow-operators/tests/test_json_archive_load_operator.py`
- `garmin-airflow-operators/tests/test_sql_identifiers.py`
- `garmin-airflow-operators/README.md`
- `docs/postgresql.md`
- `docs/verification/0038-daily-summary-loader.md`

Changes remain unstaged and uncommitted for manual review. No commits, pushes,
merges, PRs, tags, or releases were created. No functional, integration, or
end-to-end checks were run. The existing Airflow image was used only as an
isolated unit/static-check Python environment.
