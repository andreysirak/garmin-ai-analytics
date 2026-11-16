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



if __name__ == "__main__":
    unittest.main()
