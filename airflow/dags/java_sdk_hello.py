from __future__ import annotations

from datetime import datetime

from airflow.sdk import dag, task


@dag(
    dag_id="java_sdk_hello",
    start_date=datetime(2026, 1, 1),
    schedule=None,
    catchup=False,
    tags=["garmin", "java-sdk"],
)
def java_sdk_hello() -> None:
    @task.stub(queue="java", task_id="hello_java")
    def hello_java():
        ...

    hello_java()


java_sdk_hello()
