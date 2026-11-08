from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

from airflow.dag_processing.dagbag import DagBag
from garmin_auth.operator import GarminAuthOperator
from garmin_daily_summary.operator import DailySummaryIngestionOperator
from garmin_sleep.operator import SleepIngestionOperator


def test_garmin_ingestion_dag_has_auth_gate_and_parallel_jobs() -> None:
    dags_path = Path(__file__).parents[1] / "dags"
    dag_bag = DagBag(dag_folder=str(dags_path))

    assert not dag_bag.import_errors
    dag = dag_bag.dags["garmin_daily_ingestion"]
    auth = dag.get_task("ensure_garmin_token")
    summary = dag.get_task("ingest_daily_summary")
    sleep = dag.get_task("ingest_sleep")

    assert isinstance(auth, GarminAuthOperator)
    assert isinstance(summary, DailySummaryIngestionOperator)
    assert isinstance(sleep, SleepIngestionOperator)
    assert auth.namespace == "garmin-auth"
    assert auth.service_account_name == "garmin-auth"
    auth_api_key = next(
        env for env in auth.env_vars if env.name == "GARMIN_API_KEY"
    )
    assert auth_api_key.value_from.secret_key_ref.name == "garmin-api-credentials"
    auth_token_namespace = next(
        env for env in auth.env_vars if env.name == "GARMIN_TOKEN_SECRET_NAMESPACE"
    )
    assert auth_token_namespace.value == "airflow-jobs"

    assert summary.upstream_task_ids == {"ensure_garmin_token"}
    assert sleep.upstream_task_ids == {"ensure_garmin_token"}
    assert auth.downstream_task_ids == {"ingest_daily_summary", "ingest_sleep"}
    assert summary.arguments[1] == sleep.arguments[1]
    assert all(task.retries == dag.default_args["retries"] for task in dag.tasks)

    template = dag.get_template_env().from_string(summary.arguments[1])
    start_date = datetime(2026, 11, 8, 12, 30)
    assert (
        template.render(
            dag_run=SimpleNamespace(conf={}, logical_date=None, start_date=start_date),
            params={"date": None},
            ds="",
        )
        == "2026-11-08"
    )
    assert (
        template.render(
            dag_run=SimpleNamespace(
                conf={}, logical_date=datetime(2026, 11, 7), start_date=start_date
            ),
            params={"date": None},
            ds="2026-11-07",
        )
        == "2026-11-07"
    )
    assert (
        template.render(
            dag_run=SimpleNamespace(
                conf={"date": "2026-11-06"},
                logical_date=None,
                start_date=start_date,
            ),
            params={"date": None},
            ds="",
        )
        == "2026-11-06"
    )

    for ingestion_task in (summary, sleep):
        assert ingestion_task.namespace == "airflow-jobs"
        assert ingestion_task.service_account_name == "airflow-job"
        assert ingestion_task.do_xcom_push is False
        token_volume = next(
            volume
            for volume in ingestion_task.volumes
            if volume.name == "garmin-access-token"
        )
        assert token_volume.secret.secret_name == "garmin-access-token"
        token_mount = next(
            mount
            for mount in ingestion_task.volume_mounts
            if mount.name == "garmin-access-token"
        )
        assert token_mount.read_only is True
