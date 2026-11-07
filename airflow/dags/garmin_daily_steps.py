from __future__ import annotations

from datetime import date, datetime, timedelta

from airflow.sdk import Param, dag, get_current_context, task


@dag(
    dag_id="garmin_daily_steps",
    start_date=datetime(2026, 1, 1),
    schedule=None,
    catchup=False,
    params={"date": Param(default=None, type=["null", "string"])},
    tags=["garmin", "ingestion", "java-sdk"],
)
def garmin_daily_steps() -> None:
    @task(task_id="resolve_target_date")
    def resolve_target_date() -> str:
        context = get_current_context()
        dag_run = context.get("dag_run")
        configured_date = None

        if dag_run and dag_run.conf:
            configured_date = dag_run.conf.get("date")
        if configured_date is None:
            configured_date = context["params"].get("date")
        if configured_date is None:
            return context["logical_date"].date().isoformat()

        parsed = date.fromisoformat(str(configured_date))
        return parsed.isoformat()

    @task.stub(
        queue="java",
        task_id="fetch_steps",
        retries=2,
        retry_delay=timedelta(minutes=1),
    )
    def fetch_steps(target_date: str):
        ...

    @task.stub(
        queue="java",
        task_id="fetch_sleep",
        retries=2,
        retry_delay=timedelta(minutes=1),
    )
    def fetch_sleep(target_date: str):
        ...

    target_date = resolve_target_date()
    fetch_steps(target_date)
    fetch_sleep(target_date)


garmin_daily_steps()
