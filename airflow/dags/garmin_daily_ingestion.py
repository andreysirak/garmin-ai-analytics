"""Launch independent daily Garmin ingestion jobs in Kubernetes Pods."""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow.sdk import DAG, Param
from garmin_daily_summary.operator import DailySummaryIngestionOperator
from garmin_sleep.operator import SleepIngestionOperator


PROCESSING_DATE = (
    "{{ (dag_run.conf.get('date') if dag_run and dag_run.conf else params.date) or ds }}"
)


with DAG(
    dag_id="garmin_daily_ingestion",
    description="Ingest Garmin daily summary and sleep data into the raw-data PVC",
    schedule="@daily",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    params={
        "date": Param(
            default=None,
            type=["null", "string"],
            pattern=r"^\d{4}-\d{2}-\d{2}$",
            description="Optional YYYY-MM-DD date for a manual ingestion run",
        )
    },
    default_args={"retries": 2, "retry_delay": timedelta(minutes=1)},
    max_active_runs=1,
    tags=["garmin", "ingestion", "kubernetes"],
) as dag:
    ingest_daily_summary = DailySummaryIngestionOperator(
        task_id="ingest_daily_summary",
        date=PROCESSING_DATE,
    )
    ingest_sleep = SleepIngestionOperator(
        task_id="ingest_sleep",
        date=PROCESSING_DATE,
    )
