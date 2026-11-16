"""Exercise the real Make wrappers against fake CLIs, without deploying anything."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from credential_test_support import ROOT, private_json, synthetic_credentials

FAKE_CLI = r'''
import json, os, sys
from pathlib import Path
tool = Path(sys.argv[0]).name
args = sys.argv[1:]
with Path(os.environ["CALLS"]).open("a") as stream:
    stream.write(json.dumps({"tool": tool, "args": args}) + "\n")
state = json.loads(Path(os.environ["STATE"]).read_text())
if tool == "fake-make":
    sys.exit(1 if os.environ.get("FAIL_GOAL") in args else 0)
if tool == "kubectl":
    if args[:1] == ["patch"] and "--local" in args:
        print(Path(args[args.index("--filename") + 1]).read_text())
    elif "rollout" in args:
        sys.exit(1 if os.environ.get("FAIL_ROLLOUT") else 0)
    else:
        sys.exit(90)
elif tool == "kind" and args[:2] == ["get", "clusters"]:
    print("synthetic" if state["exists"] else "")
elif tool == "kind" and args[:2] == ["get", "nodes"]:
    print("\n".join(state["names"]))
elif tool == "docker" and args[:1] == ["inspect"]:
    if os.environ.get("FAIL_INSPECT"):
        sys.exit(1)
    print(json.dumps([state["node"]]))
else:
    sys.exit(90)
'''


class StackCommandsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        for tool in ("fake-make", "kubectl", "kind", "docker", "helm"):
            executable = self.bin / tool
            executable.write_text(f"#!{sys.executable}\n" + FAKE_CLI)
            executable.chmod(0o700)
        self.storage = self.directory / "postgresql"
        self.configuration = {
            "kind": "Cluster", "apiVersion": "kind.x-k8s.io/v1alpha4", "name": "synthetic",
            "nodes": [{
                "role": "control-plane", "image": "kindest/node:synthetic",
                "extraMounts": [
                    {"hostPath": str(self.directory / "dags"), "containerPath": "/mnt/dags", "readOnly": True},
                    {"hostPath": str(self.directory / "raw"), "containerPath": "/mnt/raw"},
                    {"hostPath": str(self.storage / "airflow"), "containerPath": "/mnt/postgresql-airflow"},
                    {"hostPath": str(self.storage / "garmin-analytics"), "containerPath": "/mnt/postgresql-garmin-analytics"},
                ],
                "extraPortMappings": [
                    {"containerPort": 30080, "hostPort": 8080, "listenAddress": "127.0.0.1", "protocol": "TCP"},
                    {"containerPort": 30432, "hostPort": 5432, "listenAddress": "127.0.0.1", "protocol": "TCP"},
                ],
            }],
        }
        self.config_file = self.directory / "kind.json"
        self.config_file.write_text(json.dumps(self.configuration))
        self.state_file = self.directory / "state.json"
        self.state = {
            "exists": True, "names": ["synthetic-control-plane"],
            "node": {
                "Config": {"Image": "kindest/node:synthetic"},
                "Mounts": [{"Type": "bind", "Source": item["hostPath"],
                            "Destination": item["containerPath"], "RW": not item.get("readOnly", False)}
                           for item in self.configuration["nodes"][0]["extraMounts"]],
                "HostConfig": {"PortBindings": {
                    "30080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}],
                    "30432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "5432"}],
                    "6443/tcp": [{"HostIp": "127.0.0.1", "HostPort": "65000"}],
                }},
            },
        }
        for item in self.configuration["nodes"][0]["extraMounts"]:
            Path(item["hostPath"]).mkdir(parents=True, exist_ok=True)
        for database in ("airflow", "garmin-analytics"):
            (self.storage / database / ".volume-id").write_text(database + ":" + "1" * 32 + "\n")
        self.credentials = private_json(self.directory / "credentials.json", synthetic_credentials())
        self.calls_file = self.directory / "calls.jsonl"
        self.environment = {
            **os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}",
            "GARMIN_DATABASE_STORAGE_ROOT": str(self.storage),
            "STATE": str(self.state_file), "CALLS": str(self.calls_file),
        }

    def invoke(self, target, extra_environment=None):
        self.state_file.write_text(json.dumps(self.state))
        self.calls_file.unlink(missing_ok=True)
        result = subprocess.run(
            ["make", "--no-print-directory", "-j4", target, "CLUSTER=synthetic",
             f"KIND_CONFIG={self.config_file}", f"CREDENTIALS_FILE={self.credentials}",
             f"AIRFLOW_CONNECTIONS_FILE={self.directory / 'absent-overrides.json'}",
             f"MAKE={self.bin / 'fake-make'}"],
            cwd=ROOT, env={**self.environment, **(extra_environment or {})},
            capture_output=True, text=True, timeout=30,
        )
        calls = [json.loads(line) for line in self.calls_file.read_text().splitlines()] \
            if self.calls_file.exists() else []
        return result, calls

    def goals(self, calls):
        return [call["args"] for call in calls if call["tool"] == "fake-make"]

    def test_startup_reuses_existing_targets_in_order_even_with_parallel_make(self):
        result, calls = self.invoke("stack-up")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.goals(calls), [["prepare-database-storage"], ["cluster-create"],
                                          ["infra-validate"], ["postgresql-up"], ["infra-up"]])
        self.assertFalse(any("delete" in call["args"] for call in calls))

    def test_fresh_checkout_can_start_with_no_cluster_or_prepared_directories(self):
        self.state["exists"] = False
        credentials = synthetic_credentials()
        credentials["airflow"]["fernet_key"] = ""
        private_json(self.credentials, credentials)
        for database in ("airflow", "garmin-analytics"):
            (self.storage / database / ".volume-id").unlink()
            (self.storage / database).rmdir()
        result, calls = self.invoke("stack-up")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.goals(calls)[0], ["prepare-database-storage"])
        self.assertFalse(any(call["tool"] == "docker" for call in calls))

    def test_restart_reuses_startup_and_waits_for_database_and_airflow_rollouts(self):
        result, calls = self.invoke("stack-restart")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.goals(calls), [["stack-up"]])
        rollouts = [call["args"] for call in calls if call["tool"] == "kubectl" and "rollout" in call["args"]]
        self.assertEqual(len(rollouts), 8)
        self.assertIn("statefulset/garmin-postgresql", rollouts[0])
        self.assertIn("statefulset/airflow-postgresql", rollouts[2])
        self.assertIn("statefulset/airflow-postgresql", rollouts[3])
        self.assertIn("deployment/airflow-api-server", rollouts[4])
        self.assertIn("statefulset/airflow-scheduler", rollouts[-2])
        self.assertIn("deployment/airflow-dag-processor", rollouts[-1])
        for call in calls:
            self.assertNotIn("delete", call["args"])
            self.assertNotIn("uninstall", call["args"])

    def test_absent_cluster_cannot_be_recreated_by_restart(self):
        self.state["exists"] = False
        result, calls = self.invoke("stack-restart")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stack-up", result.stderr)
        self.assertEqual(self.goals(calls), [])

    def test_creation_time_changes_fail_before_startup_or_restart(self):
        original = json.loads(json.dumps(self.state))
        for change in ("image", "mount", "readonly", "port", "topology"):
            self.state = json.loads(json.dumps(original))
            if change == "image":
                self.state["node"]["Config"]["Image"] = "kindest/node:old"
            elif change == "mount":
                self.state["node"]["Mounts"].pop()
            elif change == "readonly":
                self.state["node"]["Mounts"][1]["RW"] = False
            elif change == "port":
                self.state["node"]["HostConfig"]["PortBindings"]["30080/tcp"][0]["HostPort"] = "8081"
            else:
                self.state["names"].append("synthetic-worker")
            for target in ("stack-up", "stack-restart"):
                with self.subTest(change=change, target=target):
                    result, calls = self.invoke(target)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Nothing was deleted", result.stderr)
                    self.assertEqual(self.goals(calls), [])
                    self.assertFalse(any("rollout" in call["args"] for call in calls))

    def test_partial_storage_or_missing_marker_is_never_prepared_automatically(self):
        (self.storage / "airflow" / ".volume-id").unlink()
        for exists in (True, False):
            self.state["exists"] = exists
            with self.subTest(exists=exists):
                result, calls = self.invoke("stack-up")
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.goals(calls), [])

    def test_bad_credentials_stop_before_kind_inspection(self):
        for scope in ("postgresql", "airflow"):
            credentials = synthetic_credentials()
            credentials[scope] = {}
            private_json(self.credentials, credentials)
            for target in ("stack-up", "stack-restart"):
                with self.subTest(scope=scope, target=target):
                    result, calls = self.invoke(target)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(calls, [])

    def test_unsupported_creation_options_stop_without_mutation(self):
        self.configuration["networking"] = {"podSubnet": "10.244.0.0/16"}
        self.config_file.write_text(json.dumps(self.configuration))
        result, calls = self.invoke("stack-restart")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("creation settings", result.stderr)
        self.assertEqual(self.goals(calls), [])
        self.assertFalse(any(call["tool"] == "docker" for call in calls))

    def test_missing_host_directory_stops_before_reapplying_configuration(self):
        (self.directory / "raw").rmdir()
        result, calls = self.invoke("stack-up")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("host mount directory is missing", result.stderr)
        self.assertEqual(self.goals(calls), [])

    def test_lookup_and_target_failures_do_not_trigger_fallback_recreation(self):
        for environment in ({"FAIL_INSPECT": "1"}, {"FAIL_GOAL": "postgresql-up"}):
            with self.subTest(environment=environment):
                result, calls = self.invoke("stack-up", environment)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(["infra-up"], self.goals(calls))
                self.assertFalse(any("delete" in call["args"] for call in calls))

    def test_rollout_failure_stops_later_restarts(self):
        result, calls = self.invoke("stack-restart", {"FAIL_ROLLOUT": "1"})
        self.assertNotEqual(result.returncode, 0)
        rollouts = [call for call in calls if "rollout" in call["args"]]
        self.assertEqual(len(rollouts), 1)


if __name__ == "__main__":
    unittest.main()
