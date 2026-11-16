"""Create missing Secrets from the setup file; never replace conflicting live credentials."""

import argparse
import base64
import json
import os
import secrets
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

from local_credentials import ANALYTICS_PASSWORD, DEFAULT_FILE, load, value


def kubectl(context: str, *arguments: str, contents: str | None = None) -> str:
    """Capture all output and pass secret manifests only through stdin."""
    try:
        return subprocess.run(
            ["kubectl", "--context", context, *arguments], input=contents,
            capture_output=True, text=True, check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        raise ValueError("Kubernetes credential operation failed; check context and access") from None


def desired_secrets(credentials: dict, scope: str) -> list[tuple[str, str, dict]]:
    """Map each fixed Secret key to its local entry name and value."""
    if scope == "analytics":
        return [("garmin-analytics", "garmin-postgresql",
                 {"password": (ANALYTICS_PASSWORD, value(credentials, ANALYTICS_PASSWORD))})]
    password = value(credentials, "airflow.database.password")
    connection = (f"postgresql://airflow:{quote(password, safe='')}"
                  "@airflow-postgresql.airflow:5432/airflow")
    return [
        ("airflow", "airflow-postgresql", {
            "postgres-password": ("airflow.database.postgres_password",
                                  value(credentials, "airflow.database.postgres_password")),
            "password": ("airflow.database.password", password),
        }),
        ("airflow", "airflow-metadata-db", {
            "connection": ("airflow.database.password (metadata connection)", connection),
        }),
        ("airflow", "airflow-fernet-key", {
            "fernet-key": ("airflow.fernet_key", value(credentials, "airflow.fernet_key")),
        }),
        ("garmin-auth", "garmin-api-credentials", {
            "api-key": ("garmin.api_key", value(credentials, "garmin.api_key")),
        }),
    ]


def read_secret(context: str, namespace: str, name: str) -> dict | None:
    """Distinguish a missing namespace/Secret from API or authorization errors."""
    namespace_exists = kubectl(context, "get", "namespace", namespace,
                               "--ignore-not-found", "-o", "name").strip()
    if not namespace_exists:
        return None
    contents = kubectl(context, "-n", namespace, "get", "secret", name,
                       "--ignore-not-found", "-o", "json")
    if not contents.strip():
        return None
    try:
        return json.loads(contents)
    except ValueError:
        raise ValueError("Cannot read live Secret metadata") from None


def checked_secrets(context: str, credentials: dict, scope: str) -> list[dict]:
    """Check every managed Secret before returning any manifests to create."""
    missing = []
    for namespace, name, fields in desired_secrets(credentials, scope):
        existing = read_secret(context, namespace, name)
        if existing is None:
            missing.append({"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                            "metadata": {"namespace": namespace, "name": name},
                            "stringData": {key: setting for key, (_, setting) in fields.items()}})
            continue
        for key, (entry, setting) in fields.items():
            expected = base64.b64encode(setting.encode()).decode()
            if existing.get("data", {}).get(key) != expected:
                raise ValueError(f"{entry}: conflicts with Secret {namespace}/{name} key {key}; "
                                 "deployment never overwrites credentials")
    return missing


def apply(context: str, credentials: dict, scope: str) -> None:
    """Create absent credentials after all comparisons succeed, preserving existing Secrets."""
    for manifest in checked_secrets(context, credentials, scope):
        kubectl(context, "create", "-f", "-", contents=json.dumps(manifest))
    if scope == "airflow":
        create_runtime_secrets(context)


def create_runtime_secrets(context: str) -> None:
    """Keep existing session/UI Secrets; newly generated values never enter argv or logs."""
    settings = [
        ("airflow", "airflow-api-secret-key", "api-secret-key"),
        ("airflow", "airflow-jwt-secret", "jwt-secret"),
        ("airflow", "airflow-simple-auth", "passwords.json"),
        ("airflow-jobs", "garmin-access-token", "expires-at"),
    ]
    for namespace, name, key in settings:
        if read_secret(context, namespace, name) is not None:
            continue
        setting = secrets.token_hex(24)
        if key == "passwords.json":
            setting = json.dumps({"admin": setting})
        elif key == "expires-at":
            setting = "0"
        manifest = {"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                    "metadata": {"namespace": namespace, "name": name},
                    "stringData": {key: setting}}
        kubectl(context, "create", "-f", "-", contents=json.dumps(manifest))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["check", "apply"])
    parser.add_argument("--scope", choices=["analytics", "airflow"], required=True)
    parser.add_argument("--file", type=Path, default=os.environ.get("CREDENTIALS_FILE", DEFAULT_FILE))
    parser.add_argument("--context", default=os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"))
    args = parser.parse_args()
    try:
        credentials = load(args.file, args.scope)
        if args.scope == "airflow":
            from import_airflow_connections import configured_connections
            configured_connections(credentials, Path(os.environ.get("AIRFLOW_CONNECTIONS_FILE",
                                                                      "config/connections.json")))
        if args.action == "check":
            checked_secrets(args.context, credentials, args.scope)
        else:
            apply(args.context, credentials, args.scope)
    except ValueError as exception:
        sys.exit(str(exception))
    print("Credential configuration verified" if args.action == "check" else "Credentials configured")


if __name__ == "__main__":
    main()
