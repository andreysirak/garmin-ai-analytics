"""Locate native psql, including Homebrew's unlinked libpq installation."""

import os
import shutil
import subprocess
from pathlib import Path


def psql_binary() -> str:
    executable = shutil.which("psql")
    if executable:
        return executable
    brew = shutil.which("brew")
    if brew:
        try:
            result = subprocess.run(
                [brew, "--prefix", "libpq"], check=True, capture_output=True, text=True,
            )
            prefix = Path(result.stdout.strip())
            candidate = prefix / "bin" / "psql"
            if prefix.is_absolute() and candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)
        except (OSError, subprocess.CalledProcessError):
            pass
    raise SystemExit(
        "psql is required to verify 127.0.0.1:5432. On macOS: brew install libpq "
        "(found automatically, even when not on PATH). No cluster resources were changed."
    )
