"""Verify the real Mac psql and Airflow connection paths."""

import os
import subprocess

from verify_postgresql_host import main as verify_host

AIRFLOW_CHECK = '''
from airflow.models.connection import Connection
from airflow.providers.postgres.hooks.postgres import PostgresHook
# This helper runs in the scheduler process, outside a task SDK supervisor.
settings = Connection.get_connection_from_secrets("garmin_analytics_loader")
hook = PostgresHook(postgres_conn_id="garmin_analytics_loader", connection=settings)
assert settings.host == "garmin-postgresql.garmin-analytics.svc.cluster.local"
assert settings.port == 5432 and settings.login == "garmin_loader"
assert settings.schema == "garmin_analytics"
with hook.get_conn() as connection, connection.cursor() as cursor:
    cursor.execute("SELECT current_database(), current_user")
    assert cursor.fetchone() == ("garmin_analytics", "garmin_loader")
print("PASS: Airflow garmin_analytics_loader connects through the internal Service")
'''


def main() -> None:
    verify_host()
    result = subprocess.run(
        ["kubectl", "--context", os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"),
         "-n", "airflow", "exec", "airflow-scheduler-0", "-c", "scheduler", "--",
         "python", "-c", AIRFLOW_CHECK],
        text=True, capture_output=True,
    )
    if result.returncode:
        raise RuntimeError("Airflow loader connection failed; set the loader password, import "
                           "connections.local.json, and ensure PostgresHook is installed")
    print("PASS: Airflow garmin_analytics_loader connects through the internal Service", flush=True)


if __name__ == "__main__":
    main()
