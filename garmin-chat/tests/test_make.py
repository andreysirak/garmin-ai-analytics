"""Exercise the actual chat Make recipes using fake CLIs, including failure ordering."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FAKE_CLI = """
import json, os, sys
from pathlib import Path
tool = Path(sys.argv[0]).name
with Path(os.environ['CHAT_TEST_CALLS']).open('a') as stream:
    stream.write(json.dumps({'tool': tool, 'args': sys.argv[1:]}) + '\\n')
fail = os.environ.get('CHAT_TEST_FAIL')
sys.exit(1 if fail == tool or (fail == 'helm-upgrade' and tool == 'helm' and 'upgrade' in sys.argv) else 0)
"""


class ChatMakeTest(unittest.TestCase):
    def invoke(self, missing_values=False, fail="", use_defaults=False, goal="chat-up",
               port_in_use=False, chat_port=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for tool in ("helm", "docker", "kind", "kubectl"):
                executable = root / tool
                executable.write_text(f"#!{sys.executable}\n" + FAKE_CLI)
                executable.chmod(0o700)
            lsof = root / "lsof"
            lsof.write_text(f"#!/bin/sh\nexit {0 if port_in_use else 1}\n")
            lsof.chmod(0o700)
            values = root / "values.yaml"
            if not missing_values:
                values.write_text("ollama: {contextLength: 16384}\n")
            calls = root / "calls.jsonl"
            env = {**os.environ, "PATH": f"{root}:{os.environ['PATH']}", "CHAT_TEST_CALLS": str(calls),
                   "CHAT_TEST_FAIL": fail}
            env.pop("CHAT_PORT", None)
            override = "CHAT_VALUES=" if use_defaults else f"CHAT_VALUES={values}"
            command = ["make", "-j4", goal, override]
            if chat_port is not None:
                command.append(f"CHAT_PORT={chat_port}")
            result = subprocess.run(command, cwd=ROOT,
                                    env=env, text=True, capture_output=True, check=False)
            return result, [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []

    def test_builds_loads_and_waits_for_install_and_model_before_forwarding_the_ui(self):
        result, calls = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["tool"] for call in calls],
                         ["helm", "docker", "docker", "kind", "helm", "kubectl"])
        self.assertEqual(calls[0]["args"][0], "lint")
        load = calls[3]["args"]
        self.assertEqual(load[:2], ["load", "docker-image"])
        self.assertIn("garmin-chat-frontend:local", load)
        self.assertIn("garmin-chat-backend:local", load)
        self.assertEqual(load[-2:], ["--name", "garmin-ai-analytics"])
        install = calls[4]["args"]
        for flag in ("upgrade", "--install", "garmin-analytics", "--wait", "--wait-for-jobs"):
            self.assertIn(flag, install)
        self.assertTrue(any(arg.startswith("buildId=") for arg in install))
        forward = calls[-1]["args"]
        self.assertEqual(forward, ["--context", "kind-garmin-ai-analytics", "-n", "garmin-analytics",
                                   "port-forward", "--address", "127.0.0.1",
                                   "service/garmin-chat-frontend", "5173:80"])
        self.assertIn('http://127.0.0.1:5173', result.stdout)

    def test_missing_configuration_stops_before_any_tool(self):
        result, calls = self.invoke(missing_values=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_default_deployment_does_not_require_any_local_values_file(self):
        result, calls = self.invoke(use_defaults=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        for call in calls:
            if call['tool'] == 'helm':
                self.assertNotIn('-f', call['args'])

    def test_failed_upgrade_does_not_start_forwarding(self):
        result, calls = self.invoke(fail="helm-upgrade")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("kubectl", [call["tool"] for call in calls])

    def test_reconnecting_to_the_ui_does_not_build_or_deploy(self):
        result, calls = self.invoke(goal="chat-ui")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["tool"] for call in calls], ["kubectl"])
        self.assertIn("port-forward", calls[0]["args"])

    def test_occupied_default_port_uses_a_free_port_after_deployment(self):
        result, calls = self.invoke(port_in_use=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-1]["args"][-1], ":80")
        self.assertIn("Port 5173 is in use", result.stdout)
        self.assertIn("Forwarding from", result.stdout)

    def test_explicit_port_is_forwarded_as_requested(self):
        for port in (5173, 5174):
            with self.subTest(port=port):
                result, calls = self.invoke(port_in_use=True, chat_port=port)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls[-1]["args"][-1], f"{port}:80")

    def test_lint_build_and_load_failures_stop_before_deployment(self):
        for fail, expected in [("helm", ["helm"]), ("docker", ["helm", "docker"]),
                               ("kind", ["helm", "docker", "docker", "kind"])]:
            with self.subTest(fail=fail):
                result, calls = self.invoke(fail=fail)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual([call["tool"] for call in calls], expected)
