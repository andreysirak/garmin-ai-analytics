"""Daily Garmin pipeline, starting with authentication and independent raw-data ingestion."""

from __future__ import annotations

import hashlib
from datetime import datetime, timedelta

from airflow.sdk import DAG, Param
from garmin_auth.operator import GarminAuthOperator
from garmin_data_ingestion import GarminIngestionOperator

MOUNT_PATH = "/data/raw/garmin"
PROCESSING_DATE = (
    "{{ (dag_run.conf.get('date', params.date) if dag_run and dag_run.conf else params.date) "
    "or ((dag_run.logical_date or dag_run.start_date).strftime('%Y-%m-%d') if dag_run else ds) }}"
)
ATTEMPT_PATH = (
    PROCESSING_DATE
    + "/run-{{ archive_run_key(ti.dag_id, ti.run_id) }}"
    + "/attempt-{{ ti.try_number }}-{{ macros.uuid.uuid4().hex }}"
)


def archive_run_key(dag_id: str, run_id: str) -> str:
    """Keep arbitrary DAG/run identifiers bounded and safe as one path component."""
    return hashlib.sha256(f"{dag_id}\0{run_id}".encode()).hexdigest()


with DAG(
    dag_id="garmin_daily_pipeline",
    description="Daily Garmin data pipeline",
    schedule="@daily",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    user_defined_macros={"archive_run_key": archive_run_key},
    params={
        "date": Param(
            default=None,
            type=["null", "string"],
            pattern=r"^\d{4}-\d{2}-\d{2}$",
            description="Optional YYYY-MM-DD date for a manual pipeline run",
        )
    },
    default_args={"retries": 2, "retry_delay": timedelta(minutes=1)},
    max_active_runs=1,
    tags=["garmin", "pipeline", "ingestion", "kubernetes"],
) as dag:
    ensure_garmin_token = GarminAuthOperator(
        task_id="ensure_garmin_token",
    )
    ingest_daily_summary = GarminIngestionOperator(
        task_id="ingest_daily_summary",
        endpoint="/usersummary-service/usersummary/daily",
        output_dir=f"{MOUNT_PATH}/daily-summary/{ATTEMPT_PATH}",
        mount_path=MOUNT_PATH,
        date=PROCESSING_DATE,
        query_params={"calendarDate": PROCESSING_DATE},
        image="garmin-daily-summary:local",
    )
    ingest_sleep = GarminIngestionOperator(
        task_id="ingest_sleep",
        endpoint="/sleep-service/sleep/dailySleep",
        output_dir=f"{MOUNT_PATH}/sleep/{ATTEMPT_PATH}",
        mount_path=MOUNT_PATH,
        date=PROCESSING_DATE,
        query_params={"date": PROCESSING_DATE},
        image="garmin-sleep:local",
    )
    ensure_garmin_token >> [ingest_daily_summary, ingest_sleep]
