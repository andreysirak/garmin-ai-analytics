"""Read the single private setup file and validate only the requested fields."""

import base64
import json
import os
import re
import stat
from pathlib import Path

DEFAULT_FILE = Path(__file__).resolve().parents[2] / "config/credentials.local.json"
ANALYTICS_PASSWORD = "postgresql.garmin_analytics_password"
ROLE_PASSWORDS = {
    "garmin_schema_manager": "postgresql.garmin_schema_manager_password",
    "garmin_loader": "postgresql.garmin_loader_password",
}
AIRFLOW_FIELDS = ["airflow.database.postgres_password", "airflow.database.password",
                  "airflow.fernet_key", "garmin.api_key", *ROLE_PASSWORDS.values()]


def load_private_json(path: Path) -> dict:
    """Require a regular mode-0600 file; never repair permissions or expose JSON text."""
    try:
        with path.open("rb") as stream:
            info = os.fstat(stream.fileno())
            if path.is_symlink() or not stat.S_ISREG(info.st_mode):
                raise ValueError("Configuration must be a regular file, not a symbolic link")
            if stat.S_IMODE(info.st_mode) != 0o600:
                raise ValueError("Configuration file requires mode 0600; run chmod 600 on it")
            contents = json.load(stream)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ValueError("Cannot read private JSON configuration; check the file and its syntax") from None
    if not isinstance(contents, dict):
        raise ValueError("Configuration must be a JSON object")
    return contents


def value(credentials: dict, entry: str) -> str:
    """Return an exact value; an empty Fernet key explicitly disables local encryption."""
    result = credentials
    for key in entry.split("."):
        result = result.get(key) if isinstance(result, dict) else None
    if entry == "airflow.fernet_key" and result == "":
        return ""
    if (not isinstance(result, str) or not result.strip()
            or result.startswith(("SET_", "YOUR_", "CHANGE_ME"))
            or any(character in result for character in "\x00\r\n")):
        raise ValueError(f"{entry}: supply a non-placeholder, nonempty single-line value")
    if entry == "airflow.fernet_key":
        if (not re.fullmatch(r"[A-Za-z0-9_-]{43}=", result)
                or len(base64.urlsafe_b64decode(result)) != 32):
            raise ValueError("airflow.fernet_key: expected a URL-safe base64-encoded 32-byte key")
    return result


def load(path: Path, scope: str) -> dict:
    """Validate analytics, Airflow, or generated-connection configuration before any writes."""
    credentials = load_private_json(path)
    fields = {
        "analytics": [ANALYTICS_PASSWORD, *ROLE_PASSWORDS.values()],
        "airflow": AIRFLOW_FIELDS,
        "connections": list(ROLE_PASSWORDS.values()),
    }[scope]
    for entry in fields:
        value(credentials, entry)
    return credentials
