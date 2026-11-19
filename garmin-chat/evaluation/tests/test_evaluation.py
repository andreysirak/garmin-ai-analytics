import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from comparison import compare, verify_capture  # noqa: E402 - CLI modules live beside the tests directory.
from fixtures import evidence  # noqa: E402 - CLI modules live beside the tests directory.
from grading import apply_review, initial_grade  # noqa: E402 - CLI modules live beside the tests directory.
from suite import load_suite  # noqa: E402 - CLI modules live beside the tests directory.
import capture  # noqa: E402 - CLI modules live beside the tests directory.
import reporting  # noqa: E402 - CLI modules live beside the tests directory.
from transport import LocalHttp  # noqa: E402 - CLI modules live beside the tests directory.

spec = importlib.util.spec_from_file_location("evaluation_cli", ROOT / "run.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class FixturesTest(unittest.TestCase):
    def test_all_frozen_oracles_and_stable_ids(self):
        suite = load_suite()
        self.assertEqual(23, len(suite["cases"]))
        self.assertEqual(3, sum(c["split"] == "holdout" for c in suite["cases"]))
        for case in suite["cases"]:
            if case["stage"] == "explanation":
                coverage = evidence(case, suite["protocol"])["metrics"][0]["coverage"]
                self.assertEqual(
                    7,
                    sum(
                        coverage[k]
                        for k in [
                            "daysWithData",
                            "missingSummaryDays",
                            "missingMetricDays",
                            "nullMetricDays",
                            "invalidTypeDays",
                        ]
                    ),
                )

    def test_partial_average_includes_zero_and_excludes_unknown(self):
        suite = load_suite()
        case = next(c for c in suite["cases"] if c["id"] == "ANSWER-004")
        metric = evidence(case, suite["protocol"])["metrics"][0]
        self.assertEqual(1000, metric["value"])
        self.assertEqual(3, metric["coverage"]["daysWithData"])
        self.assertEqual(1, metric["coverage"]["zeroDays"])
        self.assertEqual(3, metric["coverage"]["missingDays"])

    def test_null_and_zero_are_distinct(self):
        suite = load_suite()
        cases = {c["id"]: c for c in suite["cases"]}
        self.assertIsNone(
            evidence(cases["ANSWER-002"], suite["protocol"])["metrics"][0]["value"]
        )
        self.assertEqual(
            0, evidence(cases["ANSWER-003"], suite["protocol"])["metrics"][0]["value"]
        )

    def test_wrong_expected_value_is_rejected(self):
        suite = load_suite()
        case = copy.deepcopy(next(c for c in suite["cases"] if c["id"] == "ANSWER-001"))
        case["expected_value"] = 999
        with self.assertRaises(ValueError):
            evidence(case, suite["protocol"])


class GradeTest(unittest.TestCase):
    def test_valid_executable_wrong_route_fails(self):
        case = {"stage": "routing", "expected": {"route": "CLARIFY", "target": "GOAL"}}
        status, failures, _ = initial_grade(
            case, '{"route":"ANALYZE"}', {"accepted": True, "route": "ANALYZE"}
        )
        self.assertEqual("FAIL", status)
        self.assertIn("wrong_route", failures)

    def test_correct_route_wrong_period_fails(self):
        case = {
            "stage": "routing",
            "expected": {
                "route": "ANALYZE",
                "selection": {
                    "metrics": ["totalSteps"],
                    "aggregation": "SUM",
                    "period": {"type": "LAST_N_DAYS", "days": 7},
                },
            },
        }
        decoded = {
            "accepted": True,
            "route": "ANALYZE",
            "selection": {
                "metrics": ["totalSteps"],
                "aggregation": "SUM",
                "period": {
                    "type": "LAST_WEEK",
                    "days": 0,
                    "startDate": "",
                    "endDate": "",
                },
            },
        }
        self.assertEqual("FAIL", initial_grade(case, '{"route":"ANALYZE"}', decoded)[0])

    def test_rejected_production_output_fails(self):
        self.assertEqual(
            "FAIL", initial_grade({"stage": "routing"}, "bad", {"accepted": False})[0]
        )

    def test_numeric_presence_does_not_approve_a_false_explanation(self):
        case = {"stage": "explanation", "review_checks": ["factual_accuracy"]}
        self.assertEqual(
            "NEEDS_REVIEW", initial_grade(case, "3000 means you were productive")[0]
        )

    def test_review_requires_hashes_exact_checks_reviewer_and_rationale(self):
        row = {
            "status": "NEEDS_REVIEW",
            "review_required": ["coverage"],
            "input_sha256": "a",
            "output_sha256": "b",
        }
        good = {
            "input_sha256": "a",
            "output_sha256": "b",
            "reviewer": "human",
            "rationale": "Coverage stated correctly",
            "checks": {"coverage": "PASS"},
        }
        self.assertEqual("PASS", apply_review(row, good)["status"])
        for change in [{"output_sha256": "old"}, {"checks": {}}, {"reviewer": ""}]:
            with self.assertRaises(ValueError):
                apply_review(row, {**good, **change})
        self.assertEqual(
            "FAIL",
            apply_review(row, {**good, "checks": {"coverage": "FAIL"}})["status"],
        )

    def test_human_review_cannot_erase_deterministic_failure(self):
        row = {
            "status": "FAIL",
            "review_required": ["coverage"],
            "input_sha256": "a",
            "output_sha256": "b",
        }
        annotation = {
            "input_sha256": "a",
            "output_sha256": "b",
            "reviewer": "human",
            "rationale": "Reviewed",
            "checks": {"coverage": "PASS"},
        }
        self.assertEqual("FAIL", apply_review(row, annotation)["status"])


class ComparisonTest(unittest.TestCase):
    def row(self, case, status, trial=1):
        return dict(
            stage="routing",
            case_id=case,
            trial=trial,
            status=status,
            input_sha256="same",
        )

    def test_regression_improvement_missing_and_inconclusive(self):
        a = [
            self.row("a", "PASS"),
            self.row("b", "FAIL"),
            self.row("c", "PASS"),
            self.row("d", "FAIL"),
        ]
        b = [self.row("a", "FAIL"), self.row("b", "PASS"), self.row("d", "ERROR")]
        m = {"protocol_sha256": "same"}
        result = compare(m, a, m, b)
        self.assertEqual(["routing/a/1"], result["regressions"])
        self.assertEqual(["routing/b/1"], result["improvements"])
        self.assertEqual(["routing/c/1"], result["missing"])
        self.assertEqual(["routing/d/1"], result["inconclusive"])

    def test_reject_protocol_change_and_duplicate_trials(self):
        with self.assertRaises(ValueError):
            compare({"protocol_sha256": "a"}, [], {"protocol_sha256": "b"}, [])
        with self.assertRaises(ValueError):
            compare(
                {"protocol_sha256": "a"},
                [self.row("x", "PASS")] * 2,
                {"protocol_sha256": "a"},
                [],
            )

    def test_report_flags_model_and_prompt_changes(self):
        a = {"protocol_sha256": "same", "model": "a", "production_sha256": "x"}
        b = {"protocol_sha256": "same", "model": "b", "production_sha256": "y"}
        result = compare(a, [], b, [])
        self.assertTrue(result["multiple_recorded_factors_changed"])

    def test_capture_integrity_rejects_missing_trials_and_changed_response(self):
        from suite import digest

        case = {"id": "ROUTE-001", "stage": "routing"}
        plan = {"cases": [case], "mode": "model", "repetitions": 1}
        manifest = {"plan": plan, "protocol_sha256": digest(plan)}
        row = self.row("ROUTE-001", "PASS")
        row.update(
            case_sha256=digest(case),
            input={"a": 1},
            input_sha256=digest({"a": 1}),
            response={"message": "captured"},
            output_sha256=digest({"message": "captured"}),
        )
        completion = {"trial_count": 1}
        verify_capture(manifest, completion, [row])
        with self.assertRaises(ValueError):
            verify_capture(manifest, completion, [])
        row["response"] = {"message": "edited"}
        with self.assertRaises(ValueError):
            verify_capture(manifest, completion, [row])


class RunnerTest(unittest.TestCase):
    def test_no_network_in_list_and_existing_files_cannot_be_overwritten(self):
        with (
            patch.object(
                LocalHttp, "json", side_effect=AssertionError("Network forbidden")
            ),
            patch("builtins.print"),
        ):
            self.assertEqual(0, cli.main(["list"]))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "file.json"
            reporting.save(path, {"x": 1})
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                reporting.save(path, {"x": 2})

    def test_quantiles_and_errors_are_not_quality_passes(self):
        result = reporting.describe(
            [
                dict(stage="model", status="PASS", elapsed_seconds=1),
                dict(stage="model", status="FAIL", elapsed_seconds=3),
                dict(stage="model", status="ERROR", elapsed_seconds=100),
            ]
        )
        self.assertEqual(2, result["model"]["median_seconds"])
        self.assertEqual(3, result["model"]["p95_seconds"])
        self.assertEqual(1, result["model"]["counts"]["ERROR"])

    def test_endpoint_rejects_external_hosts_and_embedded_credentials(self):
        for url in [
            "https://example.org",
            "http://127.0.0.1@evil.test",
            "http://u:p@localhost",
            "http://localhost/?token=x",
        ]:
            with self.assertRaises(ValueError):
                LocalHttp(url)

    def test_loopback_transport_and_no_redirects(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "http://example.invalid")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"version":"fake"}')

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            http = LocalHttp(f"http://127.0.0.1:{server.server_port}")
            self.assertEqual({"version": "fake"}, http.json("/api/version"))
            with self.assertRaises(Exception):
                http.json("/redirect")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class CaptureTest(unittest.TestCase):
    def test_runner_records_success_operational_error_and_pending_review(self):
        from types import SimpleNamespace
        import contextlib
        import io

        suite = load_suite()
        small = copy.deepcopy(suite)
        small["cases"] = [
            suite["cases"][0],
            next(c for c in suite["cases"] if c["id"] == "ANSWER-001"),
        ]

        class Bridge:
            def call(self, action, items, protocol):
                if action == "export":
                    return [
                        {"messages": [{"role": "user", "content": item["question"]}]}
                        for item in items
                    ]
                return [
                    {
                        "accepted": True,
                        "route": "ANALYZE",
                        "selection": small["cases"][0]["expected"]["selection"],
                    }
                ]

        class Provider:
            def __init__(self, *args):
                self.calls = 0

            def json(self, path, payload=None):
                if path == "/api/version":
                    return {"version": "stub-not-real-model"}
                if path == "/api/tags":
                    return {"models": [{"name": "test", "digest": "fixed"}]}
                if path == "/api/show":
                    return {"parameters": "synthetic transport stub"}
                self.calls += 1
                if self.calls == 2:
                    raise OSError("simulated timeout")
                return {
                    "done": True,
                    "done_reason": "stop",
                    "message": {
                        "content": json.dumps({"route": "ANALYZE"})
                        if self.calls == 1
                        else "You took 3000 steps."
                    },
                }

        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            (repo / "data/evaluations").mkdir(parents=True)
            out = repo / "data/evaluations/run"
            args = SimpleNamespace(
                split="regression",
                mode="model",
                stage="all",
                repetitions=2,
                model="test",
                ollama_url="http://localhost:11434",
                timeout=1,
                output=str(out),
                deployment_id=None,
            )
            with (
                patch.object(capture, "REPO", repo),
                patch.object(capture, "load_suite", return_value=small),
                patch.object(capture, "JavaBridge", Bridge),
                patch.object(capture, "LocalHttp", Provider),
                patch.object(
                    capture, "production_fingerprint", return_value="fingerprint"
                ),
                patch.object(capture, "command", return_value="source"),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(2, capture.run(args))
                captured = reporting.rows(out / "results.jsonl")
                self.assertEqual(
                    ["PASS", "ERROR", "NEEDS_REVIEW", "NEEDS_REVIEW"],
                    [r["status"] for r in captured],
                )
                self.assertEqual(
                    captured[2]["input_sha256"], captured[3]["input_sha256"]
                )
                self.assertEqual(
                    4, reporting.read(out / "completion.json")["trial_count"]
                )
                self.assertEqual(0o700, out.stat().st_mode & 0o777)
                self.assertEqual(2, len(reporting.read(out / "review-template.json")))
                with self.assertRaises(FileExistsError):
                    capture.run(args)

    def test_application_refuses_without_synthetic_acknowledgement(self):
        from types import SimpleNamespace

        args = SimpleNamespace(
            split="regression",
            mode="application",
            synthetic_database=False,
            deployment_id=None,
        )
        with patch.object(
            capture, "JavaBridge", side_effect=AssertionError("No Java launch allowed")
        ):
            with self.assertRaises(ValueError):
                capture.run(args)


if __name__ == "__main__":
    unittest.main()
