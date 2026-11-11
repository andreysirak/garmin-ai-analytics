"""Manual, synthetic connectivity check; no Garmin API calls or data loading."""

from datetime import datetime

from airflow.sdk import DAG, task

with DAG(
    dag_id="garmin_analytics_connection_check",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["garmin", "verification", "postgresql"],
) as dag:
    @task
    def check_loader_connection():
        from airflow.providers.postgres.hooks.postgres import PostgresHook

        hook = PostgresHook(postgres_conn_id="garmin_analytics_loader")
        with hook.get_conn() as connection, connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            if cursor.fetchone() != (1,):
                raise RuntimeError("PostgreSQL connection check failed")

    check_loader_connection()
