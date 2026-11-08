"""Smoke test for Airflow's KubernetesPodOperator execution path."""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from airflow.sdk import DAG

with DAG(
    dag_id="kubernetes_hello",
    description="Runs a Hello World container as a Kubernetes Pod",
    schedule=None,
    start_date=datetime(2025, 1, 1),
    catchup=False,
    tags=["smoke-test", "kubernetes"],
    default_args={"retries": 1, "retry_delay": timedelta(seconds=30)},
) as dag:
    KubernetesPodOperator(
        task_id="hello_world",
        name="hello-world",
        namespace="airflow-jobs",
        service_account_name="airflow-job",
        image="busybox:1.37",
        cmds=["sh", "-c"],
        arguments=["echo 'Hello World from a Kubernetes Pod'"],
        in_cluster=True,
        get_logs=True,
        on_finish_action="delete_pod",
        startup_timeout_seconds=300,
    )
