"""Black-box application capture must not require Java, Ollama or Spring AI."""

import contextlib
import io
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import capture
from reporting import read, rows
from suite import load_suite


class ApplicationIsolationTest(unittest.TestCase):
    def test_application_capture_uses_only_public_http(self):
        suite = load_suite()
        subset = {**suite, "cases": [suite["cases"][0]]}

        class ApplicationHttp:
            def __init__(self, base, timeout):
                self.base = base

            def json(self, path, payload=None):
                if path != "/api/chat":
                    raise AssertionError("Unexpected endpoint: " + path)
                if payload != {"message": subset["cases"][0]["question"]}:
                    raise AssertionError("Unexpected application payload")
                return {"answer": "Synthetic answer pending human review"}

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data/evaluations").mkdir(parents=True)
            output = root / "data/evaluations/application"
            args = SimpleNamespace(
                mode="application", split="regression", stage="all",
                repetitions=1, synthetic_database=True,
                deployment_id="isolated-test", output=str(output),
                application_url="http://127.0.0.1:8081",
                ollama_url="http://127.0.0.1:11434",
                model="unused", timeout=1,
            )
            with (
                patch.object(capture, "REPO", root),
                patch.object(capture, "load_suite", return_value=subset),
                patch.object(capture, "LocalHttp", ApplicationHttp),
                patch.object(capture, "JavaBridge", side_effect=AssertionError("Java forbidden")),
                patch.object(capture, "inspect_model", side_effect=AssertionError("Ollama forbidden")),
                patch.object(capture, "production_fingerprint", side_effect=AssertionError("Backend fingerprint forbidden")),
                patch.object(capture, "command", return_value="clean"),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(2, capture.run(args))
            result = rows(output / "results.jsonl")
            self.assertEqual("NEEDS_REVIEW", result[0]["status"])
            self.assertEqual("application", result[0]["stage"])
            self.assertIsNone(read(output / "manifest.json")["model"])
            self.assertIsNone(read(output / "completion.json")["model_unchanged"])
            self.assertFalse((output / "model-info.json").exists())


if __name__ == "__main__":
    unittest.main()
