"""Resolve checked-in connection definitions with private passwords and optional overrides."""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from local_credentials import DEFAULT_FILE, ROLE_PASSWORDS, load, load_private_json, value

MANAGED = {
    "garmin_analytics_schema": "garmin_schema_manager",
    "garmin_analytics_loader": "garmin_loader",
}
DEFAULT_DEFINITIONS = Path(__file__).resolve().parents[2] / "config/connections.example.json"
REMOTE_IMPORT = '''
import json, sys
from airflow.models.connection import Connection
from airflow.utils.session import create_session
payload = json.load(sys.stdin)
connections = payload["connections"]
try:
    with create_session() as session:
        pending = []
        for name, settings in connections.items():
            existing = session.query(Connection).filter_by(conn_id=name).one_or_none()
            if existing is not None:
                entry = payload["managed_entries"].get(name)
                if entry and any(getattr(existing, key) != settings[key] for key in
                       ("conn_type", "host", "port", "schema", "login", "password")):
                    sys.exit("Airflow connection " + name + " conflicts with " + entry)
            else:
                pending.append(Connection(conn_id=name, **settings))
        session.add_all(pending)
except Exception:
    sys.exit("Cannot validate Airflow connections; check the original Fernet key and metadata access")
print("Airflow connections configured; existing entries preserved")
'''


def default_definitions() -> dict:
    """Read public definitions from the checked-in file without inferring missing settings."""
    try:
        definitions = json.loads(DEFAULT_DEFINITIONS.read_text())
    except (OSError, ValueError):
        raise ValueError("Cannot read config/connections.example.json; check its JSON syntax") from None
    if not isinstance(definitions, dict) or not MANAGED.keys() <= definitions.keys():
        raise ValueError("config/connections.example.json must define both Garmin connection IDs")
    return definitions


def validate(connections: dict) -> None:
    """Validate resolved settings and intended logins before sending passwords to Airflow."""
    if not isinstance(connections, dict) or not MANAGED.keys() <= connections.keys():
        raise ValueError("Resolved definitions must include both Garmin connection IDs")
    for name, connection in connections.items():
        if not isinstance(name, str) or not name.strip() or not isinstance(connection, dict):
            raise ValueError("Connections must have names and object settings")
        supported = {"conn_type", "description", "host", "port", "schema", "login",
                     "password", "extra", "team_name"}
        if connection.keys() - supported:
            raise ValueError("Connection definition contains unsupported fields")
        if not isinstance(connection.get("conn_type"), str) or not connection["conn_type"].strip():
            raise ValueError("Connections require conn_type")
        for key in ("description", "host", "schema", "login", "password", "team_name"):
            if connection.get(key) is not None and not isinstance(connection[key], str):
                raise ValueError("Connection text fields must be strings or null")
        extra = connection.get("extra")
        if isinstance(extra, str):
            try:
                extra = json.loads(extra)
            except ValueError:
                raise ValueError("Connection extra must contain a JSON object") from None
        if extra is not None and not isinstance(extra, dict):
            raise ValueError("Connection extra must contain a JSON object")
        port = connection.get("port")
        if port is not None and (type(port) is not int or not 1 <= port <= 65535):
            raise ValueError("Connection ports must be integers between 1 and 65535")
        if name not in MANAGED:
            continue
        if connection["conn_type"] != "postgres" or connection.get("login") != MANAGED[name]:
            raise ValueError(f"{name}: use PostgreSQL and the intended dedicated login")
        if port is None or any(not isinstance(connection.get(key), str) or not connection[key].strip()
                               for key in ("host", "schema")):
            raise ValueError(f"{name}: host, port and database schema are required")
        password = connection.get("password")
        if not isinstance(password, str) or not password:
            raise ValueError(f"{name}: resolve its password from credentials.local.json")


def configured_connections(credentials: dict, custom_file: Path | None = None) -> dict:
    """Merge field overrides/additions, resolve private passwords, and validate the result."""
    definitions = default_definitions()
    overrides = load_private_json(custom_file) if custom_file is not None and custom_file.exists() else {}
    for name, override in overrides.items():
        if not isinstance(override, dict) or not isinstance(definitions.get(name, {}), dict):
            raise ValueError("Connection overrides must be objects")
        definitions[name] = {**definitions.get(name, {}), **override}
    for name, role in MANAGED.items():
        definition = definitions[name]
        if not isinstance(definition, dict):
            raise ValueError(f"{name}: connection definition must be an object")
        if "password" in definition:
            raise ValueError(f"{name}: omit password from connection definitions; "
                             f"store it only in {ROLE_PASSWORDS[role]}")
        definition["password"] = value(credentials, ROLE_PASSWORDS[role])
    validate(definitions)
    return definitions


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("connections_file", type=Path, nargs="?", default=Path("config/connections.json"))
    parser.add_argument("--credentials-file", type=Path,
                        default=os.environ.get("CREDENTIALS_FILE", DEFAULT_FILE))
    parser.add_argument("--if-present", action="store_true", help="Legacy optional-custom-file flag")
    args = parser.parse_args()
    try:
        credentials = load(args.credentials_file, "connections")
        connections = configured_connections(credentials, args.connections_file)
        payload = {"connections": connections,
                   "managed_entries": {name: ROLE_PASSWORDS[role] for name, role in MANAGED.items()}}
        result = subprocess.run(
            ["kubectl", "--context", os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"),
             "-n", "airflow", "exec", "-i", "airflow-scheduler-0", "-c", "scheduler", "--",
             "python", "-c", REMOTE_IMPORT],
            input=json.dumps(payload), text=True, capture_output=True,
        )
        if result.returncode:
            # Only controlled messages may be shown, never arbitrary CLI/ORM output.
            for name, role in MANAGED.items():
                message = f"Airflow connection {name} conflicts with {ROLE_PASSWORDS[role]}"
                if message in result.stderr.splitlines():
                    raise ValueError(message)
            raise ValueError("Airflow connection import failed; verify metadata access and the original Fernet key")
    except ValueError as exception:
        sys.exit(str(exception))
    except OSError:
        sys.exit("Cannot run Airflow connection import")
    print("Airflow connections configured; existing entries preserved")


if __name__ == "__main__":
    main()
