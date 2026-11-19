"""Check public Make entry points without starting Java, Ollama or a database."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which("make"), "make is required for entry-point checks")
class MakeTargetsTest(unittest.TestCase):
    def run_make(self, target, *variables):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "commands.jsonl"
            for name in ("python3", "mvn"):
                stub = root / name
                stub.write_text(
                    f"#!{sys.executable}\n"
                    "import json, os, sys\n"
                    "with open(os.environ['EVAL_TEST_LOG'], 'a') as stream:\n"
                    "    stream.write(json.dumps([os.path.basename(sys.argv[0]), *sys.argv[1:]]) + '\\n')\n"
                )
                stub.chmod(0o700)
            env = dict(os.environ)
            env["PATH"] = str(root) + os.pathsep + env["PATH"]
            env["EVAL_TEST_LOG"] = str(log)
            # Keep failures independent of a developer's personal Make defaults.
            env.pop("MAKEFLAGS", None)
            result = subprocess.run(
                ["make", "--no-print-directory", target, *variables],
                cwd=REPO,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            return result, calls

    def test_model_prepares_then_forwards_explicit_settings(self):
        result, calls = self.run_make(
            "chat-eval-model", "EVAL_OUTPUT=data/evaluations/example",
            "EVAL_MODEL=installed:test", "EVAL_STAGE=routing", "EVAL_SPLIT=holdout",
            "EVAL_REPETITIONS=2", "EVAL_TIMEOUT=45", "EVAL_OLLAMA_URL=http://127.0.0.1:11435",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("mvn", calls[0][0])
        self.assertEqual(["python3", "garmin-chat/evaluation/run.py", "prepare"], calls[1])
        self.assertEqual([
            "python3", "garmin-chat/evaluation/run.py", "run", "--mode", "model",
            "--ollama-url", "http://127.0.0.1:11435", "--model", "installed:test",
            "--stage", "routing", "--split", "holdout", "--repetitions", "2",
            "--timeout", "45", "--output", "data/evaluations/example",
        ], calls[2])

    def test_application_requires_both_acknowledgement_and_identity(self):
        for variables in ((), ("EVAL_DEPLOYMENT_ID=test",), ("EVAL_SYNTHETIC_DATABASE=yes",)):
            with self.subTest(variables=variables):
                result, calls = self.run_make(
                    "chat-eval-application", "EVAL_OUTPUT=data/evaluations/example",
                    "EVAL_SYNTHETIC_DATABASE=no", "EVAL_DEPLOYMENT_ID=", *variables,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)
                self.assertIn("isolated synthetic deployment", result.stdout)

    def test_application_forwards_acknowledgement_without_stage_filter(self):
        result, calls = self.run_make(
            "chat-eval-application", "EVAL_OUTPUT=data/evaluations/app",
            "EVAL_SYNTHETIC_DATABASE=yes", "EVAL_DEPLOYMENT_ID=synthetic-abc",
            "EVAL_APPLICATION_URL=http://127.0.0.1:8082", "EVAL_REPETITIONS=1",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        run = calls[-1]
        self.assertEqual("application", run[run.index("--mode") + 1])
        self.assertIn("--synthetic-database", run)
        self.assertEqual("synthetic-abc", run[run.index("--deployment-id") + 1])
        self.assertEqual("http://127.0.0.1:8082", run[run.index("--application-url") + 1])
        self.assertEqual("1", run[run.index("--repetitions") + 1])
        self.assertNotIn("--stage", run)

    def test_live_targets_require_output_before_building(self):
        for target in ("chat-eval-model", "chat-eval-application"):
            with self.subTest(target=target):
                result, calls = self.run_make(target, "EVAL_OUTPUT=")
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)
                self.assertIn("Set EVAL_OUTPUT", result.stdout)

    def test_list_does_not_prepare_or_contact_services(self):
        result, calls = self.run_make("chat-eval-list")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["python3", "garmin-chat/evaluation/run.py", "list"]], calls)


class CatalogueTest(unittest.TestCase):
    def test_catalogue_contains_exactly_the_executable_case_ids(self):
        root = REPO / "garmin-chat/evaluation"
        cases = json.loads((root / "cases.json").read_text())["cases"]
        documented = re.findall(r"^\| ((?:ROUTE|LIMIT|ANSWER|HOLDOUT)-\d+) \|", (root / "TEST-CASES.md").read_text(), re.M)
        self.assertCountEqual([case["id"] for case in cases], documented)


if __name__ == "__main__":
    unittest.main()
