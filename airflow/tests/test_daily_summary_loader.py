"""Inspect loader DAG configuration and rendered fields without executing tasks."""

from datetime import datetime
from types import SimpleNamespace

import pytest
from garmin_airflow_operators import GarminDailySummaryLoadOperator
from garmin_airflow_operators.json_archive_load_operator import OUTPUT_DIR_XCOM_KEY
from garmin_data_ingestion.operator import (
    OUTPUT_DIR_XCOM_KEY as INGESTION_OUTPUT_DIR_XCOM_KEY,
)


def test_loader_configuration_and_dependencies(dag):
    task = dag.get_task("load_daily_summary_staging")
    assert isinstance(task, GarminDailySummaryLoadOperator)
    assert task.source_task_id == dag.get_task("ingest_daily_summary").task_id
    assert task.postgres_conn_id == "garmin_analytics_loader"
    assert task.upstream_task_ids == {"ensure_daily_summary_staging", "ingest_daily_summary"}
    assert task.trigger_rule == "all_success"
    assert not task.downstream_task_ids
    assert task.do_xcom_push is False
    assert dag.get_task("ingest_sleep").upstream_task_ids == {"ensure_garmin_token"}
    assert not dag.get_task("ingest_sleep").downstream_task_ids
    assert OUTPUT_DIR_XCOM_KEY == INGESTION_OUTPUT_DIR_XCOM_KEY


@pytest.mark.parametrize("conf, logical_date, param_date, expected", [
    ({}, None, None, "2026-11-09"),
    ({}, datetime(2026, 11, 7), None, "2026-11-07"),
    ({"date": "2026-11-06"}, None, None, "2026-11-06"),
    ({"date": "2026-10-02"}, datetime(2026, 11, 7), "2026-11-01", "2026-10-02"),
    ({}, datetime(2026, 11, 7), "2026-11-01", "2026-11-01"),
    ({"other": True}, datetime(2026, 11, 7), "2026-11-01", "2026-11-01"),
])
def test_loader_and_ingestion_render_same_processing_date(
    dag, conf, logical_date, param_date, expected
):
    context = {
        "ti": SimpleNamespace(try_number=99),
        "dag_run": SimpleNamespace(
            conf=conf, logical_date=logical_date, start_date=datetime(2026, 11, 9, 12, 30)
        ),
        "params": {"date": param_date},
        "ds": logical_date.strftime("%Y-%m-%d") if logical_date else "",
    }
    loader = dag.get_task("load_daily_summary_staging")
    ingestion = dag.get_task("ingest_daily_summary")
    for task in (loader, ingestion):
        task.render_template_fields(context, jinja_env=dag.get_template_env())
    assert loader.processing_date == ingestion.date == expected
    assert loader.json_filename == f"{expected}.json"
    assert loader.template_fields == ("processing_date", "json_filename")


def test_setup_and_loader_share_dag_destination(dag):
    setup = dag.get_task("ensure_daily_summary_staging")
    loader = dag.get_task("load_daily_summary_staging")
    assert setup.params["schema"] == loader.schema == "staging"
    assert setup.params["table"] == loader.table == "stg_garmin_daily_summary"
    assert setup.conn_id == "garmin_analytics_schema"
    assert loader.postgres_conn_id == "garmin_analytics_loader"


@pytest.mark.parametrize("schema, table", [
    ("staging", "stg_garmin_daily_summary"),
    ("select", "DailySummary"),
    ("OtherSchema", "OtherTable"),
])
def test_setup_renders_configured_destination_everywhere(dag, schema, table):
    from unittest.mock import patch

    setup = dag.get_task("ensure_daily_summary_staging")
    setup.params = {**setup.params, "schema": schema, "table": table}
    context = {"task": setup}
    setup.render_template_fields(context, jinja_env=dag.get_template_env())
    target = f'"{schema}"."{table}"'
    assert f"CREATE TABLE IF NOT EXISTS {target}" in setup.sql
    assert f"LOCK TABLE {target} IN ACCESS SHARE MODE" in setup.sql
    assert f"'{target}'::regclass" in setup.sql
    assert f"Schema migration required for {target}" in setup.sql
    assert "{{" not in setup.sql
    assert "SET LOCAL ROLE garmin_owner" in setup.sql
    # Unit-test the real SQL operator handoff with a mocked database hook.
    with patch.object(setup, "get_db_hook") as hook:
        setup.execute(context)
    hook.return_value.run.assert_called_once_with(
        sql=setup.sql, autocommit=False, parameters=None, handler=None,
        return_last=True, split_statements=False,
    )


@pytest.mark.parametrize("field, value", [
    ("schema", 'bad"schema'), ("table", "x; DROP TABLE users"),
    ("schema", "x$$"), ("table", "a" * 64),
])
def test_setup_rejects_unsafe_identifiers_before_database(dag, field, value):
    from unittest.mock import patch

    from airflow.exceptions import AirflowException

    setup = dag.get_task("ensure_daily_summary_staging")
    setup.params = {**setup.params, field: value}
    with patch.object(setup, "get_db_hook") as hook, \
         pytest.raises(AirflowException, match="SQL identifiers must"):
        setup.render_template_fields({"task": setup}, jinja_env=dag.get_template_env())
    hook.assert_not_called()


def test_run_params_cannot_redirect_setup_away_from_loader_destination(dag):
    setup = dag.get_task("ensure_daily_summary_staging")
    loader = dag.get_task("load_daily_summary_staging")
    context = {"task": setup, "params": {"schema": "OtherSchema", "table": "OtherTable"}}
    setup.render_template_fields(context, jinja_env=dag.get_template_env())
    assert f'CREATE TABLE IF NOT EXISTS "{loader.schema}"."{loader.table}"' in setup.sql
    assert '"OtherSchema"."OtherTable"' not in setup.sql
