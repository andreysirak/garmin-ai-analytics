"""Verify the Mac endpoint with the bootstrap password, without printing credentials."""

import base64
import json
import os
import subprocess
import sys

from postgresql_client import psql_binary


def main() -> None:
    client = psql_binary()
    context = os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics")
    result = subprocess.run(
        ["kubectl", "--context", context, "-n", "garmin-analytics", "get", "secret",
         "garmin-postgresql", "-o", "json"],
        check=True, capture_output=True, text=True,
    )
    password = base64.b64decode(json.loads(result.stdout)["data"]["password"]).decode()
    environment = {**os.environ, "PGCONNECT_TIMEOUT": "10", "PGPASSFILE": "/dev/null"}
    command = [
        client, "-X", "-w", "-h", "127.0.0.1", "-p", "5432", "-U", "garmin_analytics",
        "-d", "garmin_analytics", "-v", "ON_ERROR_STOP=1", "-Atc",
        "SELECT current_database(), current_user;",
    ]
    # Verify authentication as well as reachability; never fall back to a .pgpass.
    environment.pop("PGPASSWORD", None)
    environment.pop("PGHOSTADDR", None)
    unauthenticated = subprocess.run(command, env=environment, capture_output=True, text=True)
    if "no password supplied" not in unauthenticated.stderr:
        sys.exit("Mac PostgreSQL endpoint did not reject a passwordless connection as expected")
    authenticated = subprocess.run(
        command, env={**environment, "PGPASSWORD": password}, capture_output=True, text=True,
    )
    if authenticated.returncode or authenticated.stdout.strip() != "garmin_analytics|garmin_analytics":
        sys.exit("Password-authenticated psql connection to 127.0.0.1:5432 failed")
    print("PASS: Mac psql connects to 127.0.0.1:5432 with password authentication")


if __name__ == "__main__":
    main()
