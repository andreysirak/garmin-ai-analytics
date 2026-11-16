"""Exercise credential preservation and fail-closed bootstrap without a cluster."""

import os
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BOOTSTRAP = Path(__file__).resolve().parents[1] / "scripts/bootstrap.sh"


class PostgreSQLBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        kubectl = self.directory / "kubectl"
        kubectl.write_text('''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  *"get secret garmin-postgresql"*)
    case "$SCENARIO" in
      existing) echo secret/garmin-postgresql ;;
      lookup-error) exit 1 ;;
    esac ;;
  *"create secret generic garmin-postgresql"*)
    cat > "$PASSWORD_FILE"
    if [[ "$SCENARIO" == create-error ]]; then exit 1; fi ;;
  *) exit 2 ;;
esac
''')
        kubectl.chmod(0o700)
        # Credential snapshot behavior is tested independently with synthetic Secrets.
        # Here record the bootstrap ordering without touching repository-local state.
        python = self.directory / "python3"
        python.write_text(f'''#!/usr/bin/env bash
set -eu
if [[ "$1" == */database_credentials.py ]]; then
  printf 'credentials %s\\n' "$*" >> "$CALLS"
  if [[ "$SCENARIO" == restore-error && "$2" == restore ]]; then exit 1; fi
  exit 0
fi
exec {shlex.quote(sys.executable)} "$@"
''')
        python.chmod(0o700)

    def bootstrap(self, scenario):
        environment = {
            **os.environ,
            "PATH": f"{self.directory}:{os.environ['PATH']}",
            "KUBE_CONTEXT": "synthetic-context",
            "SCENARIO": scenario,
            "CALLS": str(self.directory / "calls"),
            "PASSWORD_FILE": str(self.directory / "password"),
        }
        result = subprocess.run(
            ["bash", str(BOOTSTRAP), "--postgresql-only"],
            env=environment, capture_output=True, text=True,
        )
        calls = (self.directory / "calls").read_text()
        if scenario != "restore-error":
            self.assertIn("--context synthetic-context -n garmin-analytics", calls)
        self.assertNotIn("airflow", calls)
        return result, calls

    def test_existing_secret_is_preserved(self):
        result, calls = self.bootstrap("existing")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("create secret", calls)
        self.assertFalse((self.directory / "password").exists())
        self.assertIn("restore --database garmin-analytics", calls.splitlines()[0])
        self.assertIn("save --database garmin-analytics", calls.splitlines()[-1])

    def test_missing_secret_is_generated_without_exposing_password(self):
        result, calls = self.bootstrap("missing")
        self.assertEqual(result.returncode, 0, result.stderr)
        password = (self.directory / "password").read_text()
        self.assertRegex(password, r"^[0-9a-f]{48}$")
        self.assertNotIn(password, result.stdout + result.stderr + calls)
        self.assertIn("--from-file=password=/dev/stdin", calls)

    def test_lookup_error_does_not_create_credentials(self):
        result, calls = self.bootstrap("lookup-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("create secret", calls)

    def test_creation_error_is_reported(self):
        result, _ = self.bootstrap("create-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("created", result.stdout)

    def test_credential_restore_failure_stops_before_secret_creation(self):
        result, calls = self.bootstrap("restore-error")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("create secret", calls)
        self.assertNotIn("save --database", calls)


if __name__ == "__main__":
    unittest.main()
