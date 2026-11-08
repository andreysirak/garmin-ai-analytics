from pathlib import Path

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
    assert summary.upstream_task_ids == {"ensure_garmin_token"}
    assert sleep.upstream_task_ids == {"ensure_garmin_token"}
    assert auth.downstream_task_ids == {"ingest_daily_summary", "ingest_sleep"}
    assert summary.arguments[1] == sleep.arguments[1]
    assert all(task.retries == dag.default_args["retries"] for task in dag.tasks)
