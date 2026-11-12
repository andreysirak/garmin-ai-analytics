"""Inspect loader DAG configuration and rendered fields without executing tasks."""

from datetime import datetime
from types import SimpleNamespace
from unittest.mock import ANY, MagicMock

import pytest
from garmin_airflow_operators import GarminDailySummaryLoadOperator
from garmin_data_ingestion.operator import OUTPUT_JSON_FILE_XCOM_KEY


def test_loader_configuration_and_dependencies(dag):
    task = dag.get_task("load_daily_summary_staging")
    assert isinstance(task, GarminDailySummaryLoadOperator)
    ingestion = dag.get_task("ingest_daily_summary")
    assert task.json_file == ingestion.output_json_file
    assert task.json_file.operator is ingestion
    assert task.json_file.key == OUTPUT_JSON_FILE_XCOM_KEY
    assert task.postgres_conn_id == "garmin_analytics_loader"
    assert task.upstream_task_ids == {"ensure_daily_summary_staging", "ingest_daily_summary"}
    assert task.trigger_rule == "all_success"
    assert not task.downstream_task_ids
    assert task.do_xcom_push is False
    assert dag.get_task("ingest_sleep").upstream_task_ids == {"ensure_garmin_token"}
    assert not dag.get_task("ingest_sleep").downstream_task_ids
    assert not hasattr(task, "source_task_id")
    assert not hasattr(task, "json_filename")


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
        "ti": SimpleNamespace(try_number=4, xcom_pull=MagicMock()),
        "dag_run": SimpleNamespace(
            conf=conf, logical_date=logical_date, start_date=datetime(2026, 11, 9, 12, 30)
        ),
        "params": {"date": param_date},
        "ds": logical_date.strftime("%Y-%m-%d") if logical_date else "",
    }
    loader = dag.get_task("load_daily_summary_staging")
    ingestion = dag.get_task("ingest_daily_summary")
    ingestion.render_template_fields(context, jinja_env=dag.get_template_env())
    archived_file = f"{ingestion.output_dir}/{ingestion.date}.json"
    context["ti"].xcom_pull.return_value = archived_file
    context["ti"].try_number = 99
    loader.render_template_fields(context, jinja_env=dag.get_template_env())
    assert loader.processing_date == ingestion.date == expected
    assert loader.json_file == archived_file
    assert loader.json_file.endswith(f"/{expected}/4/{expected}.json")
    assert loader.template_fields == ("json_file", "processing_date")
    context["ti"].xcom_pull.assert_called_once_with(
        task_ids=ingestion.task_id, key=OUTPUT_JSON_FILE_XCOM_KEY,
        default=ANY, map_indexes=None,
    )


def test_loader_has_only_file_date_and_connection_configuration(dag):
    setup = dag.get_task("ensure_daily_summary_staging")
    loader = dag.get_task("load_daily_summary_staging")
    assert loader.sql_resource == "sql/daily_summary_upsert.sql"
    assert not hasattr(loader, "archive_root")
    assert not hasattr(loader, "schema")
    assert not hasattr(loader, "table")
    assert "schema" not in setup.params and "table" not in setup.params
    assert not dag.user_defined_filters
    assert setup.conn_id == "garmin_analytics_schema"
    assert loader.postgres_conn_id == "garmin_analytics_loader"


def test_setup_hands_fixed_table_sql_to_mocked_hook(dag):
    from unittest.mock import patch

    setup = dag.get_task("ensure_daily_summary_staging")
    context = {"params": {"schema": "ignored", "table": "ignored"}}
    setup.render_template_fields(context, jinja_env=dag.get_template_env())
    target = "staging.stg_garmin_daily_summary"
    assert f"CREATE TABLE IF NOT EXISTS {target}" in setup.sql
    assert f"LOCK TABLE {target} IN ACCESS SHARE MODE" in setup.sql
    assert f"'{target}'::regclass" in setup.sql
    assert "SET LOCAL ROLE garmin_owner" in setup.sql
    assert "{{" not in setup.sql
    with patch.object(setup, "get_db_hook") as hook:
        setup.execute(context)
    hook.return_value.run.assert_called_once_with(
        sql=setup.sql, autocommit=False, parameters=None, handler=None,
        return_last=True, split_statements=False,
    )
