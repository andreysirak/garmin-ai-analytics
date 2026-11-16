"""Exercise storage identity, mount, permission and credential failures without a cluster."""

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

    def test_host_uid_supports_new_and_existing_database_directories(self):
        identity = storage.volume_identity(self.directory, "airflow")
        for owner in ("absent", "501"):
            with self.subTest(owner=owner), patch.object(storage.os, "getuid", return_value=501), \
                 patch.object(storage, "run", return_value=f"{identity}\n501\n{owner}") as run:
                storage.check_host(self.directory, "airflow", 20)
            command = run.call_args.args[0]
            self.assertEqual(command[command.index("--user") + 1], "501:501")

    def test_rejects_mapped_or_existing_ownership_before_database_startup(self):
        identity = storage.volume_identity(self.directory, "airflow")
        for created_owner, existing_owner in (("999", "absent"), ("501", "1001")):
            with self.subTest(created_owner=created_owner, existing_owner=existing_owner), \
                 patch.object(storage.os, "getuid", return_value=501), \
                 patch.object(storage, "run", return_value=f"{identity}\n{created_owner}\n{existing_owner}"):
                with self.assertRaisesRegex(ValueError, "owned by the host UID 501"):
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



if __name__ == "__main__":
    unittest.main()
