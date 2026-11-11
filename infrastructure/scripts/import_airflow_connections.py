"""Validate and stream a manually configured loader connection into Airflow."""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

REMOTE_IMPORT = '''
import os, subprocess, sys, tempfile
fd, path = tempfile.mkstemp(suffix=".json")
try:
    with os.fdopen(fd, "w") as stream:
        stream.write(sys.stdin.read())
    result = subprocess.run(["airflow", "connections", "import", path, "--overwrite"],
                            capture_output=True)
    if result.returncode:
        sys.exit("Airflow connection import failed; check the Airflow metadata database")
finally:
    os.unlink(path)
print("Imported garmin_analytics_loader")
'''


def validate(connections: dict) -> None:
    expected = {
        "conn_type": "postgres",
        "host": "garmin-postgresql.garmin-analytics.svc.cluster.local",
        "port": 5432,
        "schema": "garmin_analytics",
        "login": "garmin_loader",
    }
    if not isinstance(connections, dict) or set(connections) != {"garmin_analytics_loader"}:
        raise ValueError("Expected only the garmin_analytics_loader connection")
    connection = connections["garmin_analytics_loader"]
    if not isinstance(connection, dict) or any(connection.get(k) != v for k, v in expected.items()):
        raise ValueError("Loader connection must use the internal Service and garmin_loader login")
    password = connection.get("password")
    if not isinstance(password, str) or not password or password == "SET_GARMIN_LOADER_PASSWORD_MANUALLY":
        raise ValueError("Fill in the garmin_loader password manually before importing")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("connections_file", type=Path)
    parser.add_argument("--if-present", action="store_true",
                        help="Skip an absent file without changing existing Airflow connections")
    args = parser.parse_args()
    try:
        contents = args.connections_file.read_text()
        validate(json.loads(contents))
    except FileNotFoundError:
        if args.if_present:
            print("Local Airflow connection file is absent; preserving existing connections")
            return
        sys.exit("Connection file is missing. Copy config/connections.example.json to the ignored "
                 "config/connections.json and fill in the loader password.")
    except (OSError, ValueError):
        sys.exit("Invalid connection file. Copy config/connections.example.json to the ignored "
                 "config/connections.json, keep the Service/login settings, and fill in the password.")
    subprocess.run(
        ["kubectl", "--context", os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"),
         "-n", "airflow", "exec", "-i", "airflow-scheduler-0", "-c", "scheduler", "--",
         "python", "-c", REMOTE_IMPORT],
        input=contents, text=True, check=True,
    )


if __name__ == "__main__":
    main()
