"""Exercise storage identity, mount, permission and credential failures without a cluster."""

import base64
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    with patch.object(sys, "path", [str(SCRIPTS), *sys.path]):
        spec.loader.exec_module(module)
    return module


storage = load_script("local_database_storage")
credentials = load_script("database_credentials")


class DatabaseStorageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "airflow"
        storage.prepare(self.directory, "airflow")

    def test_preparation_preserves_existing_identity_and_database_files(self):
        identity = storage.volume_identity(self.directory, "airflow")
        database_file = self.directory / "synthetic-data"
        database_file.write_bytes(b"preserved")
        database_file.chmod(0o600)
        storage.prepare(self.directory, "airflow")
        self.assertEqual(storage.volume_identity(self.directory, "airflow"), identity)
        self.assertEqual(database_file.read_bytes(), b"preserved")
        self.assertEqual(database_file.stat().st_mode & 0o777, 0o600)

    def test_refuses_to_adopt_unidentified_data(self):
        (self.directory / storage.MARKER).unlink()
        (self.directory / "existing-data").write_text("preserved")
        with self.assertRaisesRegex(ValueError, "unidentified nonempty"):
            storage.prepare(self.directory, "airflow")
        self.assertFalse((self.directory / storage.MARKER).exists())

    def test_missing_host_marker_fails_without_calling_docker_or_creating_it(self):
        (self.directory / storage.MARKER).unlink()
        with patch.object(storage, "run") as run:
            with self.assertRaises(OSError):
                storage.check_host(self.directory, "airflow", 20)
        run.assert_not_called()
        self.assertFalse((self.directory / storage.MARKER).exists())

    def test_rejects_wrong_database_or_colima_directory(self):
        with self.assertRaises(ValueError):
            storage.volume_identity(self.directory, "garmin-analytics")
        with patch.object(storage, "run", return_value="garmin-analytics:" + "0" * 32):
            with self.assertRaisesRegex(ValueError, "different storage"):
                storage.check_host(self.directory, "airflow", 20)

    def test_database_user_write_failure_propagates(self):
        with patch.object(storage, "run", side_effect=subprocess.CalledProcessError(1, "docker")):
            with self.assertRaises(subprocess.CalledProcessError):
                storage.check_host(self.directory, "airflow", 20)

    def test_rejects_missing_read_only_and_wrong_source_node_mounts(self):
        mount = {"Type": "bind", "RW": True, "Source": str(self.directory),
                 "Destination": "/mnt/postgresql-airflow"}
        storage.check_node_mount({"Mounts": [mount]}, self.directory, "airflow")
        for mounts in [[], [{**mount, "RW": False}], [{**mount, "Type": "volume"}],
                       [{**mount, "Source": "/wrong"}], [mount, mount]]:
            with self.subTest(mounts=mounts), self.assertRaises(ValueError):
                storage.check_node_mount({"Mounts": mounts}, self.directory, "airflow")

    def test_node_with_wrong_identity_is_rejected(self):
        analytics = self.directory.parent / "garmin-analytics"
        storage.prepare(analytics, "garmin-analytics")
        mounts = [
            {"Type": "bind", "RW": True, "Source": str(self.directory.parent / database),
             "Destination": settings["node_path"]}
            for database, settings in storage.DATABASES.items()
        ]
        with patch.object(storage, "STORAGE_ROOT", self.directory.parent), \
             patch.object(storage, "run", side_effect=[json.dumps([{"Mounts": mounts}]), "wrong"]):
            with self.assertRaisesRegex(ValueError, "expected host storage"):
                storage.check_node("synthetic")


class DatabaseCredentialsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        root = patch.object(credentials, "STORAGE_ROOT", self.root)
        root.start()
        self.addCleanup(root.stop)
        self.database = "garmin-analytics"
        self.secret = {
            "apiVersion": "v1", "kind": "Secret", "type": "Opaque",
            "metadata": {"namespace": self.database, "name": "garmin-postgresql"},
            "data": {"password": base64.b64encode(b"synthetic-only").decode()},
        }

    def save_snapshot(self):
        with patch.object(credentials, "read_secret", return_value=self.secret):
            credentials.save("kind-synthetic", self.database)

    def test_snapshot_is_private_and_omits_cluster_specific_metadata(self):
        self.save_snapshot()
        snapshot = self.root / "credentials/garmin-analytics.json"
        self.assertEqual(snapshot.stat().st_mode & 0o777, 0o600)
        self.assertEqual(snapshot.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(json.loads(snapshot.read_text()), [self.secret])

    def test_missing_secret_is_restored_over_stdin(self):
        self.save_snapshot()
        with patch.object(credentials, "read_secret", return_value=None), \
             patch.object(credentials, "kubectl") as kubectl:
            credentials.restore("kind-synthetic", self.database)
        self.assertEqual(kubectl.call_args.args, ("kind-synthetic", "create", "-f", "-"))
        self.assertEqual(json.loads(kubectl.call_args.kwargs["contents"]), self.secret)

    def test_live_credentials_are_never_overwritten(self):
        self.save_snapshot()
        changed = {**self.secret, "data": {"password": base64.b64encode(b"other").decode()}}
        with patch.object(credentials, "read_secret", return_value=changed), \
             patch.object(credentials, "kubectl") as kubectl:
            with self.assertRaisesRegex(ValueError, "differ"):
                credentials.restore("kind-synthetic", self.database)
        kubectl.assert_not_called()

    def test_retained_data_cannot_get_fresh_credentials(self):
        volume = self.root / self.database
        volume.mkdir()
        (volume / "pgdata").mkdir()
        with patch.object(credentials, "read_secret", return_value=None):
            with self.assertRaisesRegex(ValueError, "original credentials"):
                credentials.restore("kind-synthetic", self.database)
        self.assertFalse((self.root / "credentials").exists())

    def test_failed_export_leaves_previous_snapshot_intact(self):
        self.save_snapshot()
        snapshot = self.root / "credentials/garmin-analytics.json"
        previous = snapshot.read_bytes()
        with patch.object(credentials, "read_secret", return_value=None):
            with self.assertRaises(ValueError):
                credentials.save("kind-synthetic", self.database)
        self.assertEqual(snapshot.read_bytes(), previous)

    def test_api_failure_is_not_treated_as_a_missing_secret(self):
        self.save_snapshot()
        with patch.object(credentials, "read_secret",
                          side_effect=subprocess.CalledProcessError(1, "kubectl")), \
             patch.object(credentials, "kubectl") as kubectl:
            with self.assertRaises(subprocess.CalledProcessError):
                credentials.restore("kind-synthetic", self.database)
        kubectl.assert_not_called()

    def test_unsafe_snapshot_is_rejected_before_api_writes(self):
        self.save_snapshot()
        snapshot = self.root / "credentials/garmin-analytics.json"
        snapshot.chmod(0o644)
        with patch.object(credentials, "kubectl") as kubectl:
            with self.assertRaisesRegex(ValueError, "0600"):
                credentials.restore("kind-synthetic", self.database)
        kubectl.assert_not_called()

    def test_unexpected_secret_and_invalid_base64_are_rejected(self):
        for item in [
            {**self.secret, "metadata": {"namespace": "other", "name": "garmin-postgresql"}},
            {**self.secret, "data": {"password": "not-base64"}},
        ]:
            with self.subTest(item=item), self.assertRaises(ValueError):
                credentials.validate([item], self.database)

    def test_airflow_snapshot_requires_original_fernet_and_database_keys(self):
        items = [
            {"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
             "metadata": {"namespace": "airflow", "name": name},
             "data": {key: base64.b64encode(f"synthetic-{name}-{key}".encode()).decode()
                      for key in keys}}
            for name, keys in credentials.SECRETS["airflow"].items()
        ]
        with patch.object(credentials, "read_secret", side_effect=items):
            credentials.save("kind-synthetic", "airflow")
        with patch.object(credentials, "read_secret", return_value=None), \
             patch.object(credentials, "kubectl") as kubectl:
            credentials.restore("kind-synthetic", "airflow")
        restored = {item["metadata"]["name"]: item["data"] for item in (
            json.loads(call.kwargs["contents"]) for call in kubectl.call_args_list
        )}
        originals = {item["metadata"]["name"]: item["data"] for item in items}
        self.assertEqual(restored, originals)
        self.assertEqual(restored["airflow-fernet-key"], originals["airflow-fernet-key"])


if __name__ == "__main__":
    unittest.main()
