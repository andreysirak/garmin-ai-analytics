#!/usr/bin/env python3
"""Validate local Garmin credentials and write only the API key to stdout."""

from __future__ import annotations

import sys
from pathlib import Path

from local_credentials import load_private_json, value


def load_api_key(path: Path) -> str:
    return value(load_private_json(path), "garmin.api_key")


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
    except (OSError, ValueError) as error:
        print(f"Invalid Garmin credentials configuration: {error}", file=sys.stderr)
        return 1

    if not validate_only:
        sys.stdout.buffer.write(api_key.encode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
