"""Check fail-safe deployment preflight and connection credential boundaries."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f"infrastructure/scripts/{name}.py")
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "path", [str(ROOT / "infrastructure/scripts"), *sys.path]):
        spec.loader.exec_module(module)
    return module


preflight = load_script("check_postgresql_host_port")
connections = load_script("import_airflow_connections")
client = load_script("postgresql_client")


class PostgreSQLClientTest(unittest.TestCase):
    def test_prefers_psql_on_path_without_calling_brew(self):
        with patch.object(client.shutil, "which", return_value="/usr/bin/psql"), \
             patch.object(client.subprocess, "run") as run:
            self.assertEqual(client.psql_binary(), "/usr/bin/psql")
        run.assert_not_called()

    def test_finds_unlinked_homebrew_libpq(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "bin/psql"
            executable.parent.mkdir()
            executable.touch(mode=0o700)
            response = subprocess.CompletedProcess([], 0, directory + "\n", "")
            with patch.object(client.shutil, "which", side_effect=[None, "/opt/homebrew/bin/brew"]), \
                 patch.object(client.subprocess, "run", return_value=response):
                self.assertEqual(client.psql_binary(), str(executable))

    def test_reports_missing_client(self):
        with patch.object(client.shutil, "which", return_value=None):
            with self.assertRaisesRegex(SystemExit, "brew install libpq"):
                client.psql_binary()

    def test_homebrew_lookup_failure_reports_install_instructions(self):
        with patch.object(client.shutil, "which", side_effect=[None, "/opt/homebrew/bin/brew"]), \
             patch.object(client.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "brew")):
            with self.assertRaisesRegex(SystemExit, "brew install libpq"):
                client.psql_binary()


class PostgreSQLPreflightTest(unittest.TestCase):
    def test_accepts_only_exact_loopback_mapping(self):
        self.assertTrue(preflight.valid_mapping({
            "30432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5432"}],
        }))
        for binding in ({}, {"30432/tcp": None}, {"30432/tcp": [
            {"HostIp": "0.0.0.0", "HostPort": "5432"},
        ]}, {"30432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5433"}]}):
            with self.subTest(binding=binding):
                self.assertFalse(preflight.valid_mapping(binding))

    def test_missing_mapping_only_inspects_and_reports_backup_instructions(self):
        response = subprocess.CompletedProcess([], 0, json.dumps([{"HostConfig": {}}]), "")
        with patch.object(preflight.subprocess, "run", return_value=response) as run, \
             patch("sys.argv", ["check", "--cluster", "synthetic", "--context", "kind-synthetic"]):
            with self.assertRaises(SystemExit) as error:
                preflight.main()
        self.assertIn("NOT been changed", str(error.exception))
        self.assertIn("Back up PostgreSQL, Airflow metadata", str(error.exception))
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ["docker", "inspect", "synthetic-control-plane"])


class AirflowConnectionTemplateTest(unittest.TestCase):
    def setUp(self):
        self.example = json.loads((ROOT / "config/connections.example.json").read_text())

    def test_example_requires_manual_password(self):
        with self.assertRaisesRegex(ValueError, "password manually"):
            connections.validate(self.example)

    def test_loader_can_only_use_the_internal_service_and_dedicated_role(self):
        self.example["garmin_analytics_loader"]["password"] = "synthetic-only"
        connections.validate(self.example)
        for key, value in [("host", "127.0.0.1"), ("login", "garmin_analytics"), ("port", 30432)]:
            changed = json.loads(json.dumps(self.example))
            changed["garmin_analytics_loader"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                connections.validate(changed)

    def test_automatic_import_skips_missing_file_without_touching_airflow(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = str(Path(directory) / "absent.json")
            with patch("sys.argv", ["import", "--if-present", missing]), \
                 patch.object(connections.subprocess, "run") as run:
                connections.main()
        run.assert_not_called()

    def test_manual_import_reports_missing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = str(Path(directory) / "absent.json")
            with patch("sys.argv", ["import", missing]), \
                 patch.object(connections.subprocess, "run") as run:
                with self.assertRaisesRegex(SystemExit, "Connection file is missing"):
                    connections.main()
        run.assert_not_called()

    def test_automatic_import_rejects_present_placeholder_without_touching_airflow(self):
        with patch("sys.argv", ["import", "--if-present", str(ROOT / "config/connections.example.json")]), \
             patch.object(connections.subprocess, "run") as run:
            with self.assertRaisesRegex(SystemExit, "Invalid connection file"):
                connections.main()
        run.assert_not_called()

    def test_automatic_import_streams_valid_file_to_airflow(self):
        self.example["garmin_analytics_loader"]["password"] = "synthetic-only"
        contents = json.dumps(self.example)
        with tempfile.TemporaryDirectory() as directory:
            configured = Path(directory) / "connections.json"
            configured.write_text(contents)
            with patch("sys.argv", ["import", "--if-present", str(configured)]), \
                 patch.object(connections.subprocess, "run") as run:
                connections.main()
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.kwargs["input"], contents)
        self.assertTrue(run.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
