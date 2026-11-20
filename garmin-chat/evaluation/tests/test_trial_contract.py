"""Contract tests for independent trial execution and provider adapters."""

import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ollama import identity_unchanged, model_identity
from trial import execute_trial, request_payload


class FakeTransport:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def json(self, path, payload=None):
        self.requests.append((path, payload))
        return self.response


class TrialContractTest(unittest.TestCase):
    def setUp(self):
        self.case = {
            "id": "ROUTE-TEST",
            "stage": "routing",
            "question": "How many steps last week?",
            "expected": {
                "route": "ANALYZE",
                "selection": {
                    "metrics": ["totalSteps"],
                    "aggregation": "SUM",
                    "period": {"type": "LAST_WEEK"},
                },
            },
        }

    def test_black_box_application_requires_no_java_bridge(self):
        transport = FakeTransport({"answer": "3,000 steps"})
        result = execute_trial(
            self.case, {}, 1, "unused", {}, {}, transport, application=True
        )
        self.assertEqual("NEEDS_REVIEW", result["status"])
        self.assertEqual("application", result["stage"])
        self.assertEqual({"message": self.case["question"]}, transport.requests[0][1])
        self.assertNotIn("production_validation", result)

    def test_application_empty_answer_is_failure(self):
        result = execute_trial(
            self.case, {}, 1, "unused", {}, {}, FakeTransport({"answer": ""}),
            application=True,
        )
        self.assertEqual("FAIL", result["status"])

    def test_model_routing_uses_injected_decoder(self):
        response = {
            "done": True,
            "done_reason": "stop",
            "message": {"content": json.dumps({"route": "ANALYZE"})},
        }
        calls = []
        def decoder(answer):
            calls.append(answer)
            return {
                "accepted": True,
                "route": "ANALYZE",
                "selection": self.case["expected"]["selection"],
            }

        result = execute_trial(
            self.case, {"messages": []}, 1, "model", {}, {},
            FakeTransport(response), validate_route=decoder,
        )
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, len(calls))

    def test_transport_failure_is_error_not_model_failure(self):
        class Unavailable:
            def json(self, path, payload):
                raise OSError("simulated failure")
        result = execute_trial(
            self.case, {}, 1, "model", {}, {}, Unavailable()
        )
        self.assertEqual("ERROR", result["status"])
        self.assertEqual("OSError", result["error_type"])

    def test_request_payload_does_not_couple_application_to_model(self):
        self.assertEqual(
            {"message": self.case["question"]},
            request_payload(self.case, {}, "model", {}, application=True),
        )


class OllamaAdapterTest(unittest.TestCase):
    def test_identity_requires_installed_digest(self):
        self.assertEqual(
            {"name": "test", "digest": "sha"},
            model_identity({"models": [{"name": "test", "digest": "sha"}]}, "test"),
        )
        with self.assertRaises(ValueError):
            model_identity({"models": []}, "test")

    def test_replacement_or_unavailable_metadata_is_not_unchanged(self):
        model = {"name": "test", "digest": "old"}
        self.assertFalse(
            identity_unchanged(FakeTransport({"models": [
                {"name": "test", "digest": "new"}
            ]}), model)
        )
        self.assertTrue(
            identity_unchanged(FakeTransport({"models": [
                {"name": "test", "digest": "old"}
            ]}), model)
        )


if __name__ == "__main__":
    unittest.main()
