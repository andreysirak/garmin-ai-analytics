"""Validate and stream manually configured analytics connections into Airflow."""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

REMOTE_IMPORT = '''
import json, os, subprocess, sys, tempfile
contents = sys.stdin.read()
fd, path = tempfile.mkstemp(suffix=".json")
try:
    with os.fdopen(fd, "w") as stream:
        stream.write(contents)
    result = subprocess.run(["airflow", "connections", "import", path, "--overwrite"],
                            capture_output=True)
    if result.returncode:
        sys.exit("Airflow connection import failed; check the Airflow metadata database")
finally:
    os.unlink(path)
print("Imported " + ", ".join(sorted(json.loads(contents))))
'''


def validate(connections: dict) -> None:
    expected = {
        "conn_type": "postgres",
        "host": "garmin-postgresql.garmin-analytics.svc.cluster.local",
        "port": 5432,
        "schema": "garmin_analytics",
    }
    logins = {
        "garmin_analytics_loader": "garmin_loader",
        "garmin_analytics_schema": "garmin_schema_manager",
    }
    # Preserve support for existing loader-only files and independently imported connections.
    if not isinstance(connections, dict) or not connections or set(connections) - logins.keys():
        raise ValueError("Expected only known analytics connections")
    for conn_id, connection in connections.items():
        if (not isinstance(connection, dict)
                or any(connection.get(k) != v for k, v in expected.items())
                or connection.get("login") != logins[conn_id]):
            raise ValueError("Analytics connections must use the internal Service and dedicated login")
        password = connection.get("password")
        if not isinstance(password, str) or not password or password.startswith("SET_GARMIN_"):
            raise ValueError("Fill in each analytics password manually before importing")


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
                 "config/connections.json and fill in the passwords.")
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
