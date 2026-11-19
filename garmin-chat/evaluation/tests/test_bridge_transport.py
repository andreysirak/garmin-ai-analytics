"""Optional offline integration: real Java bridge + loopback HTTP stub, NOT real inference."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from suite import REPO, load_suite  # noqa: E402 - CLI modules live beside the tests directory.


@unittest.skipUnless(
    os.environ.get("GARMIN_EVAL_JAVA_SMOKE") == "1",
    "Opt-in: prepare Java bridge first; no model or DB is used",
)
class BridgeTransportTest(unittest.TestCase):
    def test_cli_capture_uses_real_production_validation(self):
        outputs = {}
        for case in load_suite()["cases"]:
            if case["stage"] == "routing":
                expected = dict(case["expected"])
                if expected["route"] == "CLARIFY":
                    target = expected["target"]
                    expected["target"] = (
                        target[0] if isinstance(target, list) else target
                    )
                    expected["question"] = "Which metric or goal do you mean?"
                outputs[case["question"]] = expected

        class Handler(BaseHTTPRequestHandler):
            def respond(self, value):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())

            def do_GET(self):
                if self.path == "/api/version":
                    self.respond({"version": "http-stub-not-ollama"})
                else:
                    self.respond(
                        {
                            "models": [
                                {"name": "synthetic-stub", "digest": "not-a-real-model"}
                            ]
                        }
                    )

            def do_POST(self):
                payload = json.loads(
                    self.rfile.read(int(self.headers["Content-Length"]))
                )
                if self.path == "/api/show":
                    self.respond({"parameters": "no real inference"})
                else:
                    question = payload["messages"][1]["content"]
                    self.respond(
                        {
                            "done": True,
                            "done_reason": "stop",
                            "message": {"content": json.dumps(outputs[question])},
                        }
                    )

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        private = REPO / "data/evaluations"
        private.mkdir(parents=True, exist_ok=True)
        try:
            # The CLI requires a new output directory; TemporaryDirectory contains it.
            with tempfile.TemporaryDirectory(dir=private) as directory:
                out = Path(directory) / "http-stub-capture"
                result = subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "run.py"),
                        "run",
                        "--stage",
                        "routing",
                        "--repetitions",
                        "1",
                        "--model",
                        "synthetic-stub",
                        "--ollama-url",
                        f"http://127.0.0.1:{server.server_port}",
                        "--output",
                        str(out),
                    ],
                    text=True,
                    capture_output=True,
                    timeout=120,
                )
                self.assertEqual(2, result.returncode, result.stderr)
                captured = [
                    json.loads(line)
                    for line in (out / "results.jsonl").read_text().splitlines()
                ]
                self.assertEqual(8, len(captured))
                self.assertNotIn("ERROR", [r["status"] for r in captured])
                self.assertTrue(
                    all(r["production_validation"]["accepted"] for r in captured)
                )
                rolling = next(r for r in captured if r["case_id"] == "ROUTE-006")
                self.assertEqual(
                    "2026-11-12",
                    rolling["production_validation"]["intent"]["range"]["startDate"],
                )
                self.assertEqual(
                    3, sum(r["status"] == "NEEDS_REVIEW" for r in captured)
                )
                ledger = json.loads((out / "review-template.json").read_text())
                for entry in ledger.values():
                    entry.update(
                        reviewer="synthetic-unit-fixture",
                        rationale="Exercises review failure handling; not semantic adjudication",
                    )
                    entry["checks"] = dict.fromkeys(entry["checks"], "FAIL")
                (out / "annotations.json").write_text(json.dumps(ledger))
                review = subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "run.py"),
                        "review",
                        str(out),
                        str(out / "annotations.json"),
                        "--output",
                        str(out / "reviewed.jsonl"),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(2, review.returncode, review.stderr)
                comparison = subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / "run.py"),
                        "compare",
                        str(out),
                        str(out),
                        "--baseline-results",
                        "reviewed.jsonl",
                        "--candidate-results",
                        "reviewed.jsonl",
                        "--output",
                        str(out / "comparison.json"),
                    ],
                    capture_output=True,
                    text=True,
                    timeout=30,
                )
                self.assertEqual(0, comparison.returncode, comparison.stderr)
                self.assertEqual(
                    [], json.loads((out / "comparison.json").read_text())["regressions"]
                )

        finally:
            server.shutdown()
            server.server_close()
            thread.join()
