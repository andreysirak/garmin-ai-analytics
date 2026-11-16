"""Run the real bootstrap against a synthetic kubectl, without a live cluster."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from credential_test_support import ROOT, private_json, synthetic_credentials


class PostgreSQLBootstrapTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.credentials = synthetic_credentials()
        self.credentials.pop("garmin")  # Analytics bootstrap must not need this key.
        self.file = private_json(self.directory / "credentials.json", self.credentials)
        fake = self.directory / "kubectl"
        fake.write_text(f'''#!{sys.executable}
import base64, json, os, sys
from pathlib import Path
arguments = sys.argv[1:]
with Path(os.environ["CALLS"]).open("a") as stream:
    stream.write(json.dumps(arguments) + "\\n")
if "namespace" in arguments:
    print("namespace/garmin-analytics")
elif "get" in arguments and "secret" in arguments:
    scenario = os.environ["SCENARIO"]
    if scenario == "lookup-error":
        sys.stderr.write("private-error-value")
        sys.exit(1)
    if scenario in ("existing", "conflict"):
        password = "synthetic-bootstrap" if scenario == "existing" else "different-private-value"
        print(json.dumps({{"data": {{"password": base64.b64encode(password.encode()).decode()}}}}))
elif "create" in arguments:
    contents = sys.stdin.read()
    Path(os.environ["MANIFEST"]).write_text(contents)
    if os.environ["SCENARIO"] == "create-error":
        sys.stderr.write(contents)
        sys.exit(1)
else:
    sys.exit(2)
''')
        fake.chmod(0o700)

    def bootstrap(self, scenario):
        environment = {
            **os.environ, "PATH": f"{self.directory}:{os.environ['PATH']}",
            "KUBE_CONTEXT": "kind-synthetic", "CREDENTIALS_FILE": str(self.file),
            "CALLS": str(self.directory / "calls"), "MANIFEST": str(self.directory / "manifest"),
            "SCENARIO": scenario,
        }
        result = subprocess.run(
            ["bash", str(ROOT / "infrastructure/scripts/bootstrap.sh"), "--postgresql-only"],
            env=environment, capture_output=True, text=True,
        )
        calls = (self.directory / "calls").read_text()
        self.assertNotIn("synthetic-bootstrap", calls + result.stdout + result.stderr)
        return result, calls

    def test_existing_secret_is_preserved(self):
        result, calls = self.bootstrap("existing")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('"create"', calls)

    def test_missing_secret_uses_the_configured_password_via_stdin(self):
        result, calls = self.bootstrap("missing")
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((self.directory / "manifest").read_text())
        self.assertEqual(manifest["stringData"]["password"], "synthetic-bootstrap")
        self.assertIn('"create", "-f", "-"', calls)

    def test_conflict_names_the_local_entry_without_changing_a_secret(self):
        result, calls = self.bootstrap("conflict")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("postgresql.garmin_analytics_password", result.stderr)
        self.assertNotIn("different-private-value", result.stderr)
        self.assertNotIn('"create"', calls)

    def test_api_and_create_errors_suppress_private_output(self):
        for scenario in ("lookup-error", "create-error"):
            with self.subTest(scenario=scenario):
                result, calls = self.bootstrap(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("private-error-value", result.stderr)

    def test_make_targets_validate_fields_before_deployment_tools(self):
        for tool in ("docker", "helm", "kind"):
            fake = self.directory / tool
            fake.write_text('#!/bin/sh\nprintf "unexpected-tool-call\\n" >> "$CALLS"\nexit 90\n')
            fake.chmod(0o700)
        invalid = private_json(self.directory / "invalid.json", {})
        environment = {**os.environ, "PATH": f"{self.directory}:{os.environ['PATH']}",
                       "CALLS": str(self.directory / "preflight-calls")}
        for target in ("postgresql-up", "infra-up"):
            with self.subTest(target=target):
                result = subprocess.run(
                    ["make", target, f"CREDENTIALS_FILE={invalid}"], cwd=ROOT,
                    env=environment, capture_output=True, text=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.directory / "preflight-calls").exists())


if __name__ == "__main__":
    unittest.main()
