"""Run the setup shell script with a fake Ollama CLI; no server, network or model files."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "infrastructure/helm/garmin-chat/files/pull-model.sh"
FAKE_OLLAMA = """#!/bin/sh
echo "$*" >> "$CALLS"
case "$1" in
  list) exit "${SERVER_FAILURE:-0}" ;;
  show) test -f "$CACHED" ;;
  pull) test "${PULL_FAILURE:-0}" = 0 || exit 1; touch "$CACHED" ;;
  *) exit 99 ;;
esac
"""


class ModelInitializationTest(unittest.TestCase):
    def invoke(self, cached=False, **extra):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "ollama").write_text(FAKE_OLLAMA)
            (root / "ollama").chmod(0o700)
            cache = root / "cached"
            calls = root / "calls"
            if cached:
                cache.touch()
            env = {**os.environ, "PATH": f"{directory}:{os.environ['PATH']}", "CACHED": str(cache),
                   "CALLS": str(calls), "OLLAMA_MODEL": "synthetic:q4", "MODEL_WAIT_ATTEMPTS": "1", **extra}
            result = subprocess.run(["sh", str(SCRIPT)], env=env, text=True, capture_output=True, check=False)
            return result, calls.read_text()

    def test_pulls_missing_model_and_exposes_completion(self):
        result, calls = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("pull synthetic:q4", calls)
        self.assertIn("Model initialization complete", result.stdout)

    def test_reuses_cached_model_without_pulling(self):
        result, calls = self.invoke(cached=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("pull ", calls)
        self.assertIn("Model already cached", result.stdout)

    def test_pull_failure_does_not_report_success(self):
        result, _ = self.invoke(PULL_FAILURE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("initialization complete", result.stdout)

    def test_server_timeout_fails_before_pull(self):
        result, calls = self.invoke(SERVER_FAILURE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("pull ", calls)
        self.assertIn("server unavailable", result.stderr)
