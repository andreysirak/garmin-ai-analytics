from copy import deepcopy
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from airflow.providers.common.sql.operators.sql import SQLExecuteQueryOperator
from garmin_auth.operator import GarminAuthOperator
from garmin_data_ingestion import GarminIngestionOperator
from garmin_data_ingestion.operator import OUTPUT_DIR_XCOM_KEY


def render_and_run(
    task,
    dag,
    *,
    run_id="manual__one",
    try_number=1,
    conf=None,
    logical_date=datetime(2026, 11, 7),
    param_date=None,
):
    context = {
        "ti": SimpleNamespace(
            dag_id=dag.dag_id,
            run_id=run_id,
            try_number=try_number,
            xcom_push=MagicMock(),
        ),
        "dag_run": SimpleNamespace(
            conf=conf or {},
            logical_date=logical_date,
            start_date=datetime(2026, 11, 8, 12, 30),
        ),
        "params": {"date": param_date},
        "ds": logical_date.strftime("%Y-%m-%d") if logical_date else "",
    }
    task.render_template_fields(context, jinja_env=dag.get_template_env())
    task.execute(context)
    return context["ti"].xcom_push.call_args.kwargs["value"]


def test_authentication_gates_independent_tasks_and_retries(dag):
    auth = dag.get_task("ensure_garmin_token")
    summary = dag.get_task("ingest_daily_summary")
    sleep = dag.get_task("ingest_sleep")
    assert set(dag.task_ids) == {
        "ensure_garmin_token",
        "ingest_daily_summary",
        "ingest_sleep",
        "ensure_daily_summary_staging",
        "load_daily_summary_staging",
    }
    assert isinstance(auth, GarminAuthOperator)
    assert all(isinstance(task, GarminIngestionOperator) for task in (summary, sleep))
    assert auth.namespace == "garmin-auth"
    assert auth.service_account_name == "garmin-auth"
    auth_api_key = next(env for env in auth.env_vars if env.name == "GARMIN_API_KEY")
    assert auth_api_key.value_from.secret_key_ref.name == "garmin-api-credentials"
    auth_token_namespace = next(
        env for env in auth.env_vars if env.name == "GARMIN_TOKEN_SECRET_NAMESPACE"
    )
    assert auth_token_namespace.value == "airflow-jobs"
    assert (
        summary.upstream_task_ids == sleep.upstream_task_ids == {"ensure_garmin_token"}
    )
    assert auth.downstream_task_ids == {"ingest_daily_summary", "ingest_sleep"}
    assert summary.downstream_task_ids == {"load_daily_summary_staging"}
    assert not sleep.downstream_task_ids
    for task in dag.tasks:
        assert task.retries == 2
        assert task.retry_delay.total_seconds() == 60
        assert task.trigger_rule == "all_success"


def test_staging_setup_is_an_independent_transactional_sql_task(dag):
    task = dag.get_task("ensure_daily_summary_staging")
    assert isinstance(task, SQLExecuteQueryOperator)
    assert task.conn_id == "garmin_analytics_schema"
    assert task.autocommit is False
    assert task.split_statements is False
    assert task.do_xcom_push is False
    assert not task.upstream_task_ids
    assert task.downstream_task_ids == {"load_daily_summary_staging"}
    task.render_template_fields({"task": task}, jinja_env=dag.get_template_env())
    assert 'CREATE TABLE IF NOT EXISTS "staging"."stg_garmin_daily_summary"' in task.sql
    assert "SET LOCAL ROLE garmin_owner" in task.sql


@pytest.mark.parametrize(
    "task_id,endpoint,date_param,image",
    [
        (
            "ingest_daily_summary",
            "/usersummary-service/usersummary/daily",
            "calendarDate",
            "garmin-daily-summary:local",
        ),
        (
            "ingest_sleep",
            "/sleep-service/sleep/dailySleep",
            "date",
            "garmin-sleep:local",
        ),
    ],
)
@pytest.mark.parametrize(
    "conf,logical_date,param_date,expected",
    [
        ({}, datetime(2026, 11, 7), None, "2026-11-07"),
        ({}, None, None, "2026-11-08"),
        ({"date": "2026-11-06"}, None, None, "2026-11-06"),
        ({"date": "2026-10-02"}, datetime(2026, 11, 7), "2026-11-01", "2026-10-02"),
        ({}, datetime(2026, 11, 7), "2026-11-01", "2026-11-01"),
        ({"other": True}, datetime(2026, 11, 7), "2026-11-01", "2026-11-01"),
        ({}, datetime(2026, 1, 3), None, "2026-01-03"),
    ],
)
def test_each_task_renders_explicit_request_and_archive_date(
    dag, task_id, endpoint, date_param, image, conf, logical_date, param_date, expected
):
    task = dag.get_task(task_id)
    with patch.object(KubernetesPodOperator, "execute_sync"):
        output_dir = render_and_run(
            task, dag, conf=conf, logical_date=logical_date, param_date=param_date
        )
    assert task.cmds == ["java", "-jar", "/app/garmin-data-ingestion.jar"]
    assert task.image == image
    assert task.arguments == [
        "--endpoint",
        endpoint,
        "--date",
        expected,
        "--output-dir",
        output_dir,
        "--query-param",
        f"{date_param}={expected}",
    ]
    assert isinstance(output_dir, str)
    assert task.output_dir == output_dir
    data_type = "daily-summary" if task_id == "ingest_daily_summary" else "sleep"
    assert output_dir == f"/data/raw/garmin/{data_type}/{expected}/1"


def test_paths_use_only_endpoint_date_and_try_number(dag):
    paths = []
    with patch.object(KubernetesPodOperator, "execute_sync"):
        for task_id, run_id, try_number, dag_id in [
            ("ingest_daily_summary", "manual__one", 1, dag.dag_id),
            ("ingest_sleep", "manual__one", 1, dag.dag_id),
            ("ingest_daily_summary", "manual__two", 1, dag.dag_id),
            ("ingest_daily_summary", "backfill__one", 1, dag.dag_id),
            ("ingest_daily_summary", "manual__one", 4, dag.dag_id),
            ("ingest_daily_summary", "manual__one", 1, dag.dag_id),
            ("ingest_daily_summary", "manual__one", 1, "another_dag"),
            ("ingest_daily_summary", "manual__/../../" + "x" * 300, 1, dag.dag_id),
        ]:
            execution_dag = deepcopy(dag)
            execution_dag.dag_id = dag_id
            task = execution_dag.get_task(task_id)
            paths.append(
                render_and_run(
                    task, execution_dag, run_id=run_id, try_number=try_number
                )
            )
    summary = "/data/raw/garmin/daily-summary/2026-11-07/1"
    assert paths == [
        summary,
        "/data/raw/garmin/sleep/2026-11-07/1",
        summary,
        summary,
        "/data/raw/garmin/daily-summary/2026-11-07/4",
        summary,
        summary,
        summary,
    ]


@pytest.fixture(params=[b'{"synthetic":', b'{ "synthetic": true }\n'])
def unmarked_attempt_bytes(request):
    """A failure can leave either partial or complete JSON, without a completion marker."""
    return request.param


def test_failed_and_successful_archives_survive_a_retry(
    dag, tmp_path, unmarked_attempt_bytes
):
    attempts = []
    completed_bytes = b'{ "synthetic": true, "unknown": null, "number": 1.00 }\n'

    def synthetic_pod(operator, context):
        directory = Path(operator.arguments[5])
        directory.mkdir(parents=True, exist_ok=False)
        attempts.append(directory)
        if context["ti"].try_number == 1:
            (directory / "2026-11-07.json").write_bytes(unmarked_attempt_bytes)
            raise AirflowException("synthetic ingestion failure")
        (directory / "2026-11-07.json").write_bytes(completed_bytes)
        (directory / "_SUCCESS").touch()

    with patch.object(KubernetesPodOperator, "execute_sync", synthetic_pod):
        source_task = dag.get_task("ingest_daily_summary")
        source_task.mount_path = str(tmp_path)
        for mount in source_task.volume_mounts:
            if mount.name == "garmin-raw-data":
                mount.mount_path = str(tmp_path)
        source_task.output_dir = source_task.output_dir.replace(
            "/data/raw/garmin", str(tmp_path), 1
        )
        # Each task attempt renders a fresh copy of the DAG's source template.
        failed_task, retry, cleared = (deepcopy(source_task) for _ in range(3))
        with pytest.raises(AirflowException, match="synthetic ingestion failure"):
            render_and_run(failed_task, dag)
        output_dir = render_and_run(retry, dag, try_number=2)
        # A later task try preserves both earlier attempts.
        render_and_run(cleared, dag, try_number=3)
    assert len(set(attempts)) == 3
    assert [p.name for p in attempts] == ["1", "2", "3"]
    assert not (attempts[0] / "_SUCCESS").exists()
    assert (attempts[0] / "2026-11-07.json").read_bytes() == unmarked_attempt_bytes
    assert (attempts[1] / "2026-11-07.json").read_bytes() == completed_bytes
    assert Path(output_dir) == attempts[1]
    assert (Path(output_dir) / "_SUCCESS").read_bytes() == b""
    assert isinstance(output_dir, str) and output_dir.startswith(str(tmp_path))
    assert OUTPUT_DIR_XCOM_KEY == "output_dir"
