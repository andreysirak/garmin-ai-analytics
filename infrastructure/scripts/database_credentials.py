"""Save and reattach database/Airflow Secrets without printing their contents."""

import argparse
import base64
import json
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

from local_database_storage import MARKER, STORAGE_ROOT

SECRETS = {
    "airflow": {
        "airflow-postgresql": {"password", "postgres-password"},
        "airflow-metadata-db": {"connection"},
        "airflow-fernet-key": {"fernet-key"},
        "airflow-api-secret-key": {"api-secret-key"},
        "airflow-jwt-secret": {"jwt-secret"},
        "airflow-simple-auth": {"passwords.json"},
    },
    "garmin-analytics": {"garmin-postgresql": {"password"}},
}


def kubectl(context: str, *arguments: str, contents: str | None = None) -> str:
    """Capture API errors and stream manifests through stdin, never shell arguments."""
    return subprocess.run(
        ["kubectl", "--context", context, *arguments], input=contents,
        capture_output=True, text=True, check=True,
    ).stdout


def read_secret(context: str, database: str, name: str) -> dict | None:
    """Read only one of the selected Secrets; distinguish absence from API failure."""
    contents = kubectl(context, "-n", database, "get", "secret", name,
                       "--ignore-not-found", "-o", "json")
    if not contents.strip():
        return None
    secret = json.loads(contents)
    return {
        "apiVersion": "v1", "kind": "Secret", "type": secret.get("type", "Opaque"),
        "metadata": {"namespace": database, "name": name}, "data": secret["data"],
    }


def validate(items: list, database: str) -> None:
    """Accept exactly the expected Secret set and valid nonempty base64 data."""
    names = [item["metadata"]["name"] for item in items]
    if len(names) != len(set(names)) or set(names) != set(SECRETS[database]):
        raise ValueError("Incomplete or unexpected credential snapshot")
    for item in items:
        if (item["apiVersion"] != "v1" or item["kind"] != "Secret"
                or item["metadata"]["namespace"] != database or item["type"] != "Opaque"):
            raise ValueError("Unexpected Secret identity")
        required = SECRETS[database][item["metadata"]["name"]]
        if not required.issubset(item["data"]):
            raise ValueError("Missing Secret keys")
        for value in item["data"].values():
            if not base64.b64decode(value, validate=True):
                raise ValueError("Empty Secret value")


def save(context: str, database: str) -> None:
    """Save current Secrets in a private, atomically replaced local snapshot."""
    items = [read_secret(context, database, name) for name in SECRETS[database]]
    if any(item is None for item in items):
        raise ValueError("Required Secrets are missing; snapshot not changed")
    validate(items, database)
    directory = STORAGE_ROOT / "credentials"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(items, stream)
        temporary.replace(directory / f"{database}.json")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def restore(context: str, database: str) -> None:
    """Restore missing Secrets; refuse differing live credentials or unprotected retained data."""
    snapshot = STORAGE_ROOT / "credentials" / f"{database}.json"
    if not snapshot.exists():
        # Read every expected Secret even on first install, so API failures stop bootstrap.
        existing = [read_secret(context, database, name) for name in SECRETS[database]]
        directory = STORAGE_ROOT / database
        if directory.exists() and any(entry.name != MARKER for entry in directory.iterdir()):
            if any(item is None for item in existing):
                raise ValueError("Retained database requires its original credentials")
            save(context, database)
        return
    if (snapshot.is_symlink() or not snapshot.is_file()
            or stat.S_IMODE(snapshot.stat().st_mode) != 0o600):
        raise ValueError("Credential snapshots must be regular mode-0600 files")
    items = json.loads(snapshot.read_text())
    validate(items, database)
    missing = []
    for item in items:
        existing = read_secret(context, database, item["metadata"]["name"])
        if existing is None:
            missing.append(item)
        elif existing["data"] != item["data"]:
            raise ValueError("Live credentials differ from the snapshot; verify before exporting again")
    # Validate all live credentials before creating any missing Secret.
    for item in missing:
        kubectl(context, "create", "-f", "-", contents=json.dumps(item))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["save", "restore"])
    parser.add_argument("--database", choices=[*SECRETS, "all"], default="all")
    parser.add_argument("--context", default=os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics"))
    args = parser.parse_args()
    databases = SECRETS if args.database == "all" else [args.database]
    try:
        for database in databases:
            (save if args.action == "save" else restore)(args.context, database)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError):
        sys.exit("Database credential preservation failed; no new credentials may be generated. "
                 "Check local snapshots and cluster access; see docs/database-storage.md.")
    print("Database credentials preserved; values are not displayed")


if __name__ == "__main__":
    main()
