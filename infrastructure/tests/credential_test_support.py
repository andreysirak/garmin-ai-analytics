"""Synthetic-only configuration shared by offline infrastructure tests."""

import base64
import importlib.util
import json
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"infrastructure/scripts/{name}.py")
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "path", [str(ROOT / "infrastructure/scripts"), *sys.path]):
        spec.loader.exec_module(module)
    return module


def synthetic_credentials():
    return {
        "garmin": {"api_key": "synthetic-api-key"},
        "airflow": {
            "database": {"postgres_password": "synthetic-airflow-admin",
                         "password": "synthetic-metadata:@/%'é"},
            "fernet_key": base64.urlsafe_b64encode(b"s" * 32).decode(),
        },
        "postgresql": {
            "garmin_analytics_password": "synthetic-bootstrap",
            "garmin_schema_manager_password": "synthetic-schema:@/%'é",
            "garmin_loader_password": "synthetic-loader:@/%'é",
        },
    }


def private_json(path, contents):
    path.write_text(json.dumps(contents))
    path.chmod(0o600)
    return path
