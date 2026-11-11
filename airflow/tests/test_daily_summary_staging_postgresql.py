"""Opt-in integration checks against a disposable, empty garmin_analytics database.

Set GARMIN_STAGING_TEST_DSN to its bootstrap-admin DSN. Never use a real database:
these tests install application roles and create/drop synthetic staging tables.
Only Airflow's supervisor connection lookup is replaced; SQL runs via the real
DAG operator and PostgresHook using the restricted schema-manager login.
"""

import json
import os
import secrets
from pathlib import Path
from unittest.mock import patch

import pytest
from airflow.providers.postgres.hooks.postgres import USE_PSYCOPG3

if USE_PSYCOPG3:
    from psycopg.errors import RaiseException
else:
    from psycopg2.errors import RaiseException

pytestmark = pytest.mark.skipif(
    not os.environ.get("GARMIN_STAGING_TEST_DSN"),
    reason="Requires a disposable PostgreSQL database via GARMIN_STAGING_TEST_DSN",
)
ROOT = Path(__file__).resolve().parents[2]
TABLE = "staging.stg_garmin_daily_summary"


@pytest.fixture(scope="module")
def postgres():
    import psycopg2
    from psycopg2 import sql

    with psycopg2.connect(os.environ["GARMIN_STAGING_TEST_DSN"]) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT current_database(), to_regnamespace('staging')")
            assert cursor.fetchone() == ("garmin_analytics", None), "Use an empty test database"
            roles = (ROOT / "infrastructure/postgresql/roles.sql").read_text()
            roles = "\n".join(line for line in roles.splitlines() if not line.startswith("\\"))
            cursor.execute(roles)
            # Reapplying bootstrap must not reset passwords or remove schema-owner membership.
            cursor.execute(roles)
            password = secrets.token_urlsafe(32)
            cursor.execute(sql.SQL("ALTER ROLE garmin_schema_manager PASSWORD {}").format(
                sql.Literal(password)
            ))
        connection.commit()
        try:
            yield connection, password
        finally:
            connection.rollback()
            with connection.cursor() as cursor:
                cursor.execute("DROP SCHEMA staging CASCADE")
            connection.commit()


@pytest.fixture
def staging(postgres):
    from airflow.dag_processing.dagbag import DagBag
    from airflow.providers.common.sql.operators.sql import BaseHook
    from airflow.sdk import Connection

    admin, password = postgres
    settings = admin.get_dsn_parameters()
    connection = Connection(
        conn_id="garmin_analytics_schema",
        conn_type="postgres",
        host=settings["host"],
        port=int(settings["port"]),
        schema="garmin_analytics",
        login="garmin_schema_manager",
        password=password,
    )
    dagbag = DagBag(dag_folder=str(ROOT / "airflow/dags"))
    assert not dagbag.import_errors
    task = dagbag.dags["garmin_daily_pipeline"].get_task("ensure_daily_summary_staging")
    task.render_template_fields({}, jinja_env=task.dag.get_template_env())

    def execute():
        with patch.object(BaseHook, "get_connection", return_value=connection):
            task.execute({})

    try:
        yield admin, execute
    finally:
        admin.rollback()
        with admin.cursor() as cursor:
            cursor.execute(f"DROP TABLE IF EXISTS {TABLE} CASCADE")
        admin.commit()


RAW_FILE_PATH = "/data/raw/garmin/daily-summary/2026-11-09/1/2026-11-09.json"
PAYLOAD_CHECK = "stg_garmin_daily_summary_payload_object"


@pytest.fixture
def summary_payload():
    return {
        "userProfileId": 12,
        "calendarDate": "2026-11-09",
        "totalSteps": 8100,
        "lastSyncTimestampGMT": "2026-11-09T17:18:29.941Z",
        "wellnessStartTimeLocal": "2026-11-09T00:00:00.0",
        "bodyBatteryDynamicFeedbackEvent": {
            "eventTimestampGmt": "2026-11-09T15:33:19.567Z", "bodyBatteryLevel": "HIGH",
        },
        "endOfDayBodyBatteryDynamicFeedbackEvent": {"bodyBatteryLevel": "LOW"},
        "bodyBatteryActivityEventList": [{"eventType": "ACTIVITY", "bodyBatteryImpact": -8}],
        "unknownGarminField": {"nested": [1, None, "preserved"]},
    }


def table_snapshot(connection):
    """Capture schema, identity/ownership, constraints/indexes, and every synthetic row."""
    with connection.cursor() as cursor:
        cursor.execute("SELECT oid, relowner FROM pg_class WHERE oid = %s::regclass", (TABLE,))
        relation = cursor.fetchone()
        cursor.execute("SELECT attname, format_type(atttypid, atttypmod), attnotnull "
                       "FROM pg_attribute WHERE attrelid = %s::regclass "
                       "AND attnum > 0 AND NOT attisdropped ORDER BY attnum", (TABLE,))
        columns = cursor.fetchall()
        cursor.execute("SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                       "WHERE conrelid = %s::regclass ORDER BY conname", (TABLE,))
        constraints = cursor.fetchall()
        cursor.execute("SELECT indexrelid, pg_get_indexdef(indexrelid) FROM pg_index "
                       "WHERE indrelid = %s::regclass ORDER BY indexrelid", (TABLE,))
        indexes = cursor.fetchall()
        cursor.execute(f"SELECT to_jsonb(t) FROM {TABLE} t ORDER BY user_profile_id, calendar_date")
        rows = cursor.fetchall()
    return relation, columns, constraints, indexes, rows


def test_fresh_setup_preserves_full_response_and_supports_loader_upsert(staging, summary_payload):
    import psycopg2

    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute("SELECT tableowner FROM pg_tables WHERE schemaname = 'staging' "
                       "AND tablename = 'stg_garmin_daily_summary'")
        assert cursor.fetchone() == ("garmin_owner",)
        cursor.execute("SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                       "WHERE table_schema = 'staging' AND table_name = 'stg_garmin_daily_summary' "
                       "ORDER BY ordinal_position")
        assert cursor.fetchall() == [
            ("user_profile_id", "bigint", "NO"),
            ("calendar_date", "date", "NO"),
            ("raw_file_path", "text", "NO"),
            ("payload", "jsonb", "NO"),
        ]
        cursor.execute("SET SESSION AUTHORIZATION garmin_loader")
        cursor.execute(f"INSERT INTO {TABLE} VALUES (%s, %s, %s, %s::jsonb)",
                       (12, "2026-11-09", RAW_FILE_PATH, json.dumps(summary_payload)))
        cursor.execute("RESET SESSION AUTHORIZATION")
    admin.commit()
    before = table_snapshot(admin)
    execute()
    execute()
    assert table_snapshot(admin) == before
    with admin.cursor() as cursor:
        cursor.execute(f"SELECT user_profile_id, calendar_date::text, raw_file_path, payload FROM {TABLE}")
        assert cursor.fetchall() == [(12, "2026-11-09", RAW_FILE_PATH, summary_payload)]
        cursor.execute("SET SESSION AUTHORIZATION garmin_loader")
        corrected = {**summary_payload, "totalSteps": 8200}
        corrected_path = RAW_FILE_PATH.replace("/1/", "/2/")
        cursor.execute(f"INSERT INTO {TABLE} VALUES (%s, %s, %s, %s::jsonb) "
                       "ON CONFLICT (user_profile_id, calendar_date) "
                       "DO UPDATE SET raw_file_path = EXCLUDED.raw_file_path, payload = EXCLUDED.payload",
                       (12, "2026-11-09", corrected_path, json.dumps(corrected)))
        cursor.execute(f"SELECT raw_file_path, payload FROM {TABLE}")
        assert cursor.fetchall() == [(corrected_path, corrected)]
        with pytest.raises(psycopg2.errors.InsufficientPrivilege):
            cursor.execute("SET ROLE garmin_owner")
    admin.rollback()


@pytest.mark.parametrize("payload", [[], [{}], "text", 42, True, None])
def test_non_object_payload_is_rejected(staging, payload):
    import psycopg2

    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute("SET SESSION AUTHORIZATION garmin_loader")
        with pytest.raises(psycopg2.errors.CheckViolation, match=PAYLOAD_CHECK):
            cursor.execute(f"INSERT INTO {TABLE} VALUES (%s, %s, %s, %s::jsonb)",
                           (12, "2026-11-09", RAW_FILE_PATH, json.dumps(payload)))


@pytest.mark.parametrize("column", ["user_profile_id", "calendar_date", "raw_file_path", "payload"])
def test_all_contract_columns_are_not_null(staging, column):
    import psycopg2

    admin, execute = staging
    execute()
    values = {"user_profile_id": 12, "calendar_date": "2026-11-09",
              "raw_file_path": RAW_FILE_PATH, "payload": "{}"}
    values[column] = None
    with admin.cursor() as cursor, pytest.raises(psycopg2.errors.NotNullViolation, match=column):
        cursor.execute(f"INSERT INTO {TABLE} VALUES (%s, %s, %s, %s::jsonb)", tuple(values.values()))


def test_empty_object_is_allowed_without_metric_quality_checks(staging):
    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute(f"INSERT INTO {TABLE} VALUES (12, '2026-11-09', %s, '{{}}'::jsonb)",
                       (RAW_FILE_PATH,))
        cursor.execute(f"SELECT payload FROM {TABLE}")
        assert cursor.fetchall() == [({},)]


@pytest.mark.parametrize("ddl, section", [
    ("DROP COLUMN raw_file_path", "columns"),
    ("ALTER COLUMN raw_file_path TYPE varchar(12)", "columns"),
    ("ALTER COLUMN user_profile_id TYPE integer", "columns"),
    ("ALTER COLUMN payload DROP NOT NULL", "columns"),
    ("ALTER COLUMN raw_file_path SET DEFAULT ''", "columns"),
    ("ADD COLUMN unexpected text", "columns"),
    ("DROP CONSTRAINT stg_garmin_daily_summary_pkey", "constraints"),
    ("DROP CONSTRAINT stg_garmin_daily_summary_pkey, ADD PRIMARY KEY (user_profile_id)", "constraints"),
    (("DROP CONSTRAINT stg_garmin_daily_summary_pkey, "
      "ADD PRIMARY KEY (user_profile_id, calendar_date) DEFERRABLE"), "constraints"),
    (f"DROP CONSTRAINT {PAYLOAD_CHECK}", "constraints"),
    ((f"DROP CONSTRAINT {PAYLOAD_CHECK}, "
      "ADD CHECK (jsonb_typeof(payload) IN ('object', 'array'))"), "constraints"),
    ((f"DROP CONSTRAINT {PAYLOAD_CHECK}, "
      "ADD CHECK (jsonb_typeof(payload) = 'object') NOT VALID"), "constraints"),
])
def test_incompatible_schema_requires_migration_without_altering_table(staging, ddl, section):
    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute(f"ALTER TABLE {TABLE} {ddl}")
    admin.commit()
    before = table_snapshot(admin)
    with pytest.raises(RaiseException, match=rf"Schema migration required.*{section}: expected.*found"):
        execute()
    assert table_snapshot(admin) == before


def test_extra_unique_index_requires_migration(staging):
    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute(f"CREATE UNIQUE INDEX unexpected_unique ON {TABLE} (raw_file_path)")
    admin.commit()
    with pytest.raises(RaiseException, match="indexes: expected only a valid"):
        execute()


def test_constraint_names_do_not_affect_compatibility(staging):
    admin, execute = staging
    execute()
    with admin.cursor() as cursor:
        cursor.execute(f"ALTER TABLE {TABLE} RENAME CONSTRAINT stg_garmin_daily_summary_pkey TO renamed_key")
        cursor.execute(f"ALTER TABLE {TABLE} RENAME CONSTRAINT {PAYLOAD_CHECK} TO renamed_check")
    admin.commit()
    execute()


def test_existing_95_column_table_requires_migration_and_keeps_schema_and_rows(staging):
    admin, execute = staging
    with admin.cursor() as cursor:
        cursor.execute("SET LOCAL ROLE garmin_owner")
        cursor.execute((Path(__file__).parent / "sql/legacy_wide_daily_summary.sql").read_text())
        cursor.execute(f"INSERT INTO {TABLE} (user_profile_id, calendar_date, total_steps, "
                       "body_battery_activity_event_list) VALUES (%s, %s, %s, %s::jsonb)",
                       (12, "2026-11-09", 8100, '[{"eventType":"ACTIVITY","bodyBatteryImpact":-8}]'))
    admin.commit()
    before = table_snapshot(admin)
    assert len(before[1]) == 95
    assert len(before[4]) == 1
    with pytest.raises(RaiseException, match="Schema migration required.*four-column JSONB contract") as error:
        execute()
    assert "Existing wide tables require a reviewed migration" in str(error.value)
    assert "leaves existing schema and rows unchanged" in str(error.value)
    assert table_snapshot(admin) == before


def test_view_cannot_masquerade_as_the_staging_table(staging):
    admin, execute = staging
    with admin.cursor() as cursor:
        cursor.execute(f"CREATE VIEW {TABLE} AS SELECT 1 AS user_profile_id")
    admin.commit()
    try:
        with pytest.raises(RaiseException, match="expected an ordinary table"):
            execute()
    finally:
        with admin.cursor() as cursor:
            cursor.execute(f"DROP VIEW {TABLE}")
        admin.commit()
