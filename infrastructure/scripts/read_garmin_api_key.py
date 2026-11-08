#!/usr/bin/env python3
"""Validate local Garmin credentials and write only the API key to stdout."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any


def load_api_key(path: Path) -> str:
    if path.is_symlink():
        raise ValueError("credentials file must not be a symbolic link")

    os.chmod(path, 0o600)
    with path.open(encoding="utf-8") as credentials_file:
        credentials: Any = json.load(credentials_file)

    garmin = credentials.get("garmin") if isinstance(credentials, dict) else None
    api_key = garmin.get("api_key") if isinstance(garmin, dict) else None
    if not isinstance(api_key, str) or not api_key or api_key.strip() != api_key:
        raise ValueError("garmin.api_key must be a non-empty string without surrounding whitespace")
    return api_key


def main() -> int:
    validate_only = len(sys.argv) == 3 and sys.argv[1] == "--validate-only"
    if len(sys.argv) != (3 if validate_only else 2):
        print(
            "Usage: read_garmin_api_key.py [--validate-only] CREDENTIALS_FILE",
            file=sys.stderr,
        )
        return 2

    try:
        credentials_path = Path(sys.argv[2] if validate_only else sys.argv[1])
        api_key = load_api_key(credentials_path)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Invalid Garmin credentials configuration: {error}", file=sys.stderr)
        return 1

    if not validate_only:
        sys.stdout.buffer.write(api_key.encode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
