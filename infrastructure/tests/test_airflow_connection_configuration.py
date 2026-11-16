"""Check explicit connection definitions, password resolution, and file-free Airflow registration."""

import io
import json
import subprocess
import sys
import tempfile
import types
import unittest
from contextlib import contextmanager, redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from credential_test_support import ROOT, load_script, private_json, synthetic_credentials

connections = load_script("import_airflow_connections")


class AirflowConnectionConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.credentials = synthetic_credentials()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def test_checked_in_definitions_resolve_passwords_without_a_local_override(self):
        configured = connections.configured_connections(self.credentials, self.directory / "missing.json")
        self.assertEqual(configured["garmin_analytics_schema"]["password"],
                         self.credentials["postgresql"]["garmin_schema_manager_password"])
        self.assertEqual(configured["garmin_analytics_loader"]["password"],
                         self.credentials["postgresql"]["garmin_loader_password"])
        connections.validate(configured)

    def test_checked_in_definitions_have_both_logins_and_no_passwords(self):
        definitions = json.loads((ROOT / "config/connections.example.json").read_text())
        self.assertEqual(set(definitions), {"garmin_analytics_schema", "garmin_analytics_loader"})
        expected_logins = {"garmin_analytics_schema": "garmin_schema_manager",
                           "garmin_analytics_loader": "garmin_loader"}
        resolved = connections.configured_connections(self.credentials)
        for name, definition in definitions.items():
            self.assertNotIn("password", definition)
            self.assertEqual(definition["login"], expected_logins[name])
            self.assertEqual(definition["host"], "garmin-postgresql.garmin-analytics.svc.cluster.local")
            self.assertEqual(definition["port"], 5432)
            self.assertEqual(definition["schema"], "garmin_analytics")
            self.assertTrue(definition["description"])
            self.assertEqual({key: resolved[name][key] for key in definition}, definition)

    def test_unrelated_custom_connections_are_preserved(self):
        custom = {"other_service": {"conn_type": "http", "host": "https://example.invalid"}}
        path = private_json(self.directory / "connections.json", custom)
        configured = connections.configured_connections(self.credentials, path)
        self.assertEqual(configured["other_service"], custom["other_service"])
        self.assertIn("garmin_analytics_loader", configured)
        self.assertIn("garmin_analytics_schema", configured)

    def test_partial_local_overrides_merge_with_default_fields_before_password_resolution(self):
        overrides = {"garmin_analytics_schema": {"description": "Local schema setup",
                                                "host": "postgres.local", "port": 15432}}
        path = private_json(self.directory / "connections.json", overrides)
        configured = connections.configured_connections(self.credentials, path)
        schema = configured["garmin_analytics_schema"]
        self.assertEqual(schema["description"], "Local schema setup")
        self.assertEqual(schema["host"], "postgres.local")
        self.assertEqual(schema["port"], 15432)
        self.assertEqual(schema["login"], "garmin_schema_manager")
        self.assertEqual(schema["password"], self.credentials["postgresql"]["garmin_schema_manager_password"])
        self.assertIn("garmin_analytics_loader", configured)

    def test_passwords_in_override_definitions_are_rejected_without_echoing_values(self):
        custom = {"garmin_analytics_loader": {"password": "private-duplicated-password"}}
        path = private_json(self.directory / "connections.json", custom)
        with self.assertRaises(ValueError) as failure:
            connections.configured_connections(self.credentials, path)
        self.assertIn("postgresql.garmin_loader_password", str(failure.exception))
        self.assertNotIn("private-duplicated-password", str(failure.exception))

    def test_missing_or_malformed_checked_in_definitions_do_not_fall_back_to_hardcoded_settings(self):
        path = self.directory / "definitions.json"
        for contents in ["{", "{}", '{"garmin_analytics_schema": {}}']:
            path.write_text(contents)
            with self.subTest(contents=contents), patch.object(connections, "DEFAULT_DEFINITIONS", path):
                with self.assertRaises(ValueError):
                    connections.configured_connections(self.credentials)

    def test_default_definitions_supply_metadata_without_modifying_the_file(self):
        definitions = json.loads((ROOT / "config/connections.example.json").read_text())
        definitions["garmin_analytics_loader"]["description"] = "Description from checked-in definitions"
        path = self.directory / "definitions.json"
        path.write_text(json.dumps(definitions))
        before = path.read_bytes()
        with patch.object(connections, "DEFAULT_DEFINITIONS", path):
            resolved = connections.configured_connections(self.credentials)
        self.assertEqual(resolved["garmin_analytics_loader"]["description"],
                         "Description from checked-in definitions")
        self.assertEqual(path.read_bytes(), before)

    def test_resolved_definitions_reject_wrong_logins_and_invalid_fields(self):
        for key, setting in [("login", "garmin_analytics"), ("port", 0), ("port", True),
                             ("port", "5432"), ("host", ""), ("schema", None), ("extra", "private-invalid-json")]:
            changed = connections.configured_connections(self.credentials)
            changed["garmin_analytics_loader"][key] = setting
            with self.subTest(key=key), self.assertRaises(ValueError):
                connections.validate(changed)

    def test_cli_passes_only_path_arguments_and_stdin_passwords(self):
        path = private_json(self.directory / "credentials.json", self.credentials)
        result = subprocess.CompletedProcess([], 0, "", "")
        with patch("sys.argv", ["import", str(self.directory / "absent-custom.json"),
                                "--credentials-file", str(path)]), \
             patch.object(connections.subprocess, "run", return_value=result) as run, \
             redirect_stdout(io.StringIO()):
            connections.main()
        payload = json.loads(run.call_args.kwargs["input"])
        self.assertEqual(payload["connections"], connections.configured_connections(self.credentials))
        self.assertNotIn("synthetic-loader", str(run.call_args.args[0]))
        self.assertTrue(run.call_args.kwargs["capture_output"])

    def test_cli_never_echoes_uncontrolled_remote_error_output(self):
        path = private_json(self.directory / "credentials.json", self.credentials)
        result = subprocess.CompletedProcess([], 1, "private-output", "private-error")
        with patch("sys.argv", ["import", str(self.directory / "absent.json"), "--credentials-file", str(path)]), \
             patch.object(connections.subprocess, "run", return_value=result):
            with self.assertRaises(SystemExit) as failure:
                connections.main()
        self.assertNotIn("private-", str(failure.exception))

    def test_cli_reports_named_conflicts_without_echoing_surrounding_remote_logs(self):
        path = private_json(self.directory / "credentials.json", self.credentials)
        message = "Airflow connection garmin_analytics_loader conflicts with postgresql.garmin_loader_password"
        result = subprocess.CompletedProcess([], 1, "", "private-log-value\n" + message + "\n")
        with patch("sys.argv", ["import", str(self.directory / "absent.json"), "--credentials-file", str(path)]), \
             patch.object(connections.subprocess, "run", return_value=result):
            with self.assertRaises(SystemExit) as failure:
                connections.main()
        self.assertEqual(str(failure.exception), message)


class RemoteConnectionImportTest(unittest.TestCase):
    def run_import(self, existing=None, additional=None):
        configured = connections.configured_connections(synthetic_credentials())
        configured.update(additional or {})
        payload = {"connections": configured,
                   "managed_entries": {"garmin_analytics_schema": "postgresql.garmin_schema_manager_password",
                                       "garmin_analytics_loader": "postgresql.garmin_loader_password"}}
        session = MagicMock()
        existing = existing or {}
        session.query.return_value.filter_by.side_effect = lambda conn_id: types.SimpleNamespace(
            one_or_none=lambda: existing.get(conn_id))
        self.added = []
        session.add_all.side_effect = self.added.extend

        @contextmanager
        def create_session():
            yield session

        class Connection(types.SimpleNamespace):
            pass

        model = types.ModuleType("airflow.models.connection")
        model.Connection = Connection
        sessions = types.ModuleType("airflow.utils.session")
        sessions.create_session = create_session
        with patch.dict(sys.modules, {"airflow.models.connection": model, "airflow.utils.session": sessions}), \
             patch("sys.stdin", io.StringIO(json.dumps(payload))), \
             patch("tempfile.mkstemp", side_effect=AssertionError("No generated password files")), \
             patch("subprocess.run", side_effect=AssertionError("No file-based CLI import")), \
             redirect_stdout(io.StringIO()):
            exec(connections.REMOTE_IMPORT, {})
        return {item.conn_id: item for item in self.added}

    def test_both_ids_are_registered_with_the_definition_logins_descriptions_and_resolved_passwords(self):
        imported = self.run_import()
        expected = connections.configured_connections(synthetic_credentials())
        self.assertEqual(set(imported), set(expected))
        for name, settings in expected.items():
            for key, value in settings.items():
                self.assertEqual(getattr(imported[name], key), value)

    def test_existing_matching_credentials_are_preserved(self):
        configured = connections.configured_connections(synthetic_credentials())
        existing = {name: types.SimpleNamespace(**settings) for name, settings in configured.items()}
        self.assertEqual(self.run_import(existing), {})

    def test_existing_password_conflict_prevents_all_creations(self):
        settings = connections.configured_connections(synthetic_credentials())["garmin_analytics_loader"]
        settings["password"] = "private-different-password"
        with self.assertRaises(SystemExit) as failure:
            self.run_import({"garmin_analytics_loader": types.SimpleNamespace(**settings)})
        self.assertIn("postgresql.garmin_loader_password", str(failure.exception))
        self.assertNotIn("private-different-password", str(failure.exception))
        self.assertEqual(self.added, [])

    def test_additional_connections_are_created_and_existing_custom_entries_left_untouched(self):
        additional = {"new_service": {"conn_type": "http", "host": "https://example.invalid"},
                      "existing_service": {"conn_type": "http", "host": "https://new.invalid"}}
        existing = {"existing_service": types.SimpleNamespace(host="https://original.invalid")}
        imported = self.run_import(existing, additional)
        self.assertIn("new_service", imported)
        self.assertNotIn("existing_service", imported)
        self.assertEqual(existing["existing_service"].host, "https://original.invalid")


if __name__ == "__main__":
    unittest.main()
