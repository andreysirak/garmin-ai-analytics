"""Validate scoped setup, fail-closed Secret handling, and noninteractive SQL passwords."""

import base64
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from credential_test_support import ROOT, load_script, private_json, synthetic_credentials

config = load_script("local_credentials")
database = load_script("database_credentials")
roles = load_script("configure_postgresql_roles")


class LocalCredentialsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.path = self.directory / "credentials.local.json"
        self.credentials = synthetic_credentials()
        private_json(self.path, self.credentials)

    def test_analytics_requires_only_its_three_passwords(self):
        private_json(self.path, {"postgresql": self.credentials["postgresql"]})
        config.load(self.path, "analytics")
        with self.assertRaisesRegex(ValueError, "airflow.database.postgres_password"):
            config.load(self.path, "airflow")

    def test_airflow_requires_original_fernet_key_and_managed_connection_passwords(self):
        config.load(self.path, "airflow")
        for key in ("garmin_schema_manager_password", "garmin_loader_password"):
            changed = synthetic_credentials()
            del changed["postgresql"][key]
            private_json(self.path, changed)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, key):
                config.load(self.path, "airflow")

    def test_template_is_not_a_valid_deployment_configuration(self):
        private_json(self.path, json.loads((ROOT / "config/credentials.example.json").read_text()))
        for scope in ("analytics", "airflow"):
            with self.subTest(scope=scope), self.assertRaisesRegex(ValueError, "non-placeholder"):
                config.load(self.path, scope)

    def test_rejects_unsafe_modes_without_changing_them(self):
        for mode in (0o644, 0o640, 0o400):
            self.path.chmod(mode)
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, "0600"):
                config.load(self.path, "analytics")
            self.assertEqual(self.path.stat().st_mode & 0o777, mode)

    def test_rejects_symlinks_and_malformed_json_without_echoing_input(self):
        link = self.directory / "link.json"
        link.symlink_to(self.path)
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            config.load(link, "analytics")
        self.path.write_text('{"private-secret-value":')
        with self.assertRaises(ValueError) as failure:
            config.load(self.path, "analytics")
        self.assertNotIn("private-secret-value", str(failure.exception))

    def test_fernet_validation_and_single_line_passwords(self):
        for key in ("not-a-key", base64.urlsafe_b64encode(b"short").decode()):
            self.credentials["airflow"]["fernet_key"] = key
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "airflow.fernet_key"):
                config.value(self.credentials, "airflow.fernet_key")
        self.credentials["postgresql"]["garmin_loader_password"] = "private-value\nsecond-line"
        with self.assertRaises(ValueError) as failure:
            config.value(self.credentials, "postgresql.garmin_loader_password")
        self.assertNotIn("private-value", str(failure.exception))


class SecretConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.credentials = synthetic_credentials()

    def test_fresh_analytics_setup_streams_bootstrap_password_without_api_key(self):
        self.credentials.pop("garmin")
        with patch.object(database, "read_secret", return_value=None), \
             patch.object(database, "kubectl") as kubectl:
            database.apply("kind-synthetic", self.credentials, "analytics")
        self.assertEqual(kubectl.call_args.args, ("kind-synthetic", "create", "-f", "-"))
        manifest = json.loads(kubectl.call_args.kwargs["contents"])
        self.assertEqual(manifest["metadata"]["name"], "garmin-postgresql")
        self.assertEqual(manifest["stringData"]["password"], "synthetic-bootstrap")
        self.assertNotIn("synthetic-bootstrap", str(kubectl.call_args.args))

    def test_airflow_uses_separate_admin_password_and_url_encoded_metadata_password(self):
        manifests = database.checked_secrets
        with patch.object(database, "read_secret", return_value=None):
            items = manifests("kind-synthetic", self.credentials, "airflow")
        secrets = {item["metadata"]["name"]: item["stringData"] for item in items}
        self.assertEqual(secrets["airflow-postgresql"]["postgres-password"], "synthetic-airflow-admin")
        self.assertEqual(secrets["airflow-postgresql"]["password"], "synthetic-metadata:@/%'é")
        self.assertIn("synthetic-metadata%3A%40%2F%25%27%C3%A9", secrets["airflow-metadata-db"]["connection"])
        self.assertEqual(secrets["airflow-fernet-key"]["fernet-key"],
                         self.credentials["airflow"]["fernet_key"])

    def test_rebuild_recreates_the_same_credentials_without_snapshot_files(self):
        with patch.object(database, "read_secret", return_value=None):
            first = database.checked_secrets("kind-before", self.credentials, "airflow")
            rebuilt = database.checked_secrets("kind-after", self.credentials, "airflow")
        self.assertEqual(first, rebuilt)

    def test_matching_existing_secrets_are_preserved(self):
        def existing(context, namespace, name):
            fields = next(fields for ns, secret, fields in
                          database.desired_secrets(self.credentials, "airflow") if secret == name)
            return {"data": {key: base64.b64encode(setting.encode()).decode()
                             for key, (_, setting) in fields.items()}}
        with patch.object(database, "read_secret", side_effect=existing), \
             patch.object(database, "kubectl") as kubectl:
            self.assertEqual(database.checked_secrets("kind-synthetic", self.credentials, "airflow"), [])
        kubectl.assert_not_called()

    def test_late_conflict_prevents_all_secret_creations_and_names_only_the_entry(self):
        with patch.object(database, "read_secret", side_effect=[None, None, {"data": {}}, None]), \
             patch.object(database, "kubectl") as kubectl:
            with self.assertRaises(ValueError) as failure:
                database.apply("kind-synthetic", self.credentials, "airflow")
        self.assertIn("airflow.fernet_key", str(failure.exception))
        self.assertNotIn(self.credentials["airflow"]["fernet_key"], str(failure.exception))
        kubectl.assert_not_called()

    def test_api_errors_do_not_expose_stdin_or_stderr(self):
        error = subprocess.CalledProcessError(1, "kubectl", stderr="private-secret-value")
        with patch.object(database.subprocess, "run", side_effect=error):
            with self.assertRaises(ValueError) as failure:
                database.kubectl("kind-synthetic", "create", "-f", "-", contents="private-secret-value")
        self.assertNotIn("private-secret-value", str(failure.exception))

    def test_invalid_file_fails_before_any_cluster_calls(self):
        with tempfile.TemporaryDirectory() as directory:
            path = private_json(Path(directory) / "credentials.json", {"postgresql": {}})
            with patch("sys.argv", ["credentials", "apply", "--scope", "analytics", "--file", str(path)]), \
                 patch.object(database, "kubectl") as kubectl, redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    database.main()
        kubectl.assert_not_called()


class NoninteractiveRolesTest(unittest.TestCase):
    def setUp(self):
        self.credentials = synthetic_credentials()

    def test_new_passwords_go_to_native_psql_over_stdin_only(self):
        success = subprocess.CompletedProcess([], 0, "", "")
        with patch.object(roles, "checked_secrets"), \
             patch.object(roles, "existing_passwords", return_value=set()), \
             patch.object(roles, "check_password") as verify, \
             patch.object(roles, "psql", return_value=success) as psql:
            roles.configure("kind-synthetic", self.credentials)
        password_calls = [call for call in psql.call_args_list if call.args[1][:1] == ["-c"]]
        self.assertEqual(len(password_calls), 2)
        for call in password_calls:
            role = call.args[1][1].split()[1]
            password = config.value(self.credentials, roles.ROLE_PASSWORDS[role])
            self.assertEqual(call.args[2], password + "\n" + password + "\n")
            self.assertNotIn(password, str(call.args[:2]))
        self.assertEqual(verify.call_count, 2)

    def test_existing_passwords_are_verified_and_never_reset(self):
        with patch.object(roles, "checked_secrets"), \
             patch.object(roles, "existing_passwords", return_value=set(roles.ROLE_PASSWORDS)), \
             patch.object(roles, "check_password"), \
             patch.object(roles, "psql", return_value=subprocess.CompletedProcess([], 0)) as psql:
            roles.configure("kind-synthetic", self.credentials)
        self.assertEqual(len(psql.call_args_list), 1)  # Only the public roles/grants SQL.
        self.assertIn("CREATE ROLE garmin_loader", psql.call_args.args[2])

    def test_password_mismatch_stops_before_grants_or_password_changes(self):
        with patch.object(roles, "checked_secrets"), \
             patch.object(roles, "existing_passwords", return_value={"garmin_loader"}), \
             patch.object(roles, "check_password", side_effect=ValueError("existing password conflicts")), \
             patch.object(roles, "psql") as psql:
            with self.assertRaises(ValueError):
                roles.configure("kind-synthetic", self.credentials)
        psql.assert_not_called()

    def test_passwordless_service_is_rejected_instead_of_accepting_any_password(self):
        with patch.object(roles, "psql", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(ValueError, "require password"):
                roles.check_password("kind-synthetic", "garmin_loader", "synthetic")

    def test_rpc_uses_no_tty_and_keeps_passwords_out_of_argv(self):
        with patch.object(roles.subprocess, "run") as run:
            roles.psql("kind-synthetic", ["-c", "\\password garmin_loader"], "private-value\nprivate-value\n")
        command = run.call_args.args[0]
        self.assertNotIn("-t", command)
        self.assertNotIn("private-value", str(command))
        self.assertTrue(run.call_args.kwargs["capture_output"])


if __name__ == "__main__":
    unittest.main()
