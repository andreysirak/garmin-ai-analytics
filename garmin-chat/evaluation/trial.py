"""One evaluation trial: provider-independent execution and stage-specific grading.

The runner supplies a transport and an optional routing decoder. Neither this
module nor the evaluators import Garmin Chat or Spring AI.
"""

from datetime import datetime, timezone
import time

from fixtures import evidence
from grading import initial_grade, numeric_hints
from suite import digest


APPLICATION_REVIEW = (
    "routing_behavior",
    "factual_accuracy",
    "coverage",
    "relevance",
    "no_unsupported_claims",
)


def request_payload(case, prompt, model, settings, application=False):
    if application:
        return {"message": case["question"]}
    return {"model": model, **prompt, "stream": False, "options": settings}


def grade_application(response):
    answer = response.get("answer")
    if not isinstance(answer, str) or not answer.strip():
        return {"status": "FAIL", "failures": ["empty_or_invalid_http_answer"]}
    return {
        "status": "NEEDS_REVIEW",
        "review_required": list(APPLICATION_REVIEW),
        "limitation": (
            "HTTP text alone cannot establish route, SQL or exact evidence; "
            "attach a reviewed synthetic trace"
        ),
    }


def grade_model(case, response, protocol, validate_route):
    message = response.get("message")
    if not isinstance(message, dict):
        raise ValueError("Missing provider message object")
    if (
        not response.get("done")
        or response.get("done_reason") == "length"
        or message.get("tool_calls")
    ):
        return {"status": "FAIL", "failures": ["incomplete_or_unexpected_tool_output"]}
    answer = message.get("content", "")
    decoded = None
    result = {}
    if case["stage"] == "routing":
        decoded = validate_route(answer)
        result["production_validation"] = decoded
    status, failures, required = initial_grade(case, answer, decoded)
    result.update(status=status, failures=failures, review_required=required)
    if case["stage"] == "explanation":
        result.update(
            evidence=evidence(case, protocol),
            numeric_hints=numeric_hints(answer) if isinstance(answer, str) else [],
        )
    return result


def execute_trial(case, prompt, trial, model, settings, protocol, transport,
                  validate_route=None, application=False):
    """Execute a single trial; never silently convert transport errors to model failures."""
    stage = "application" if application else case["stage"]
    payload = request_payload(case, prompt, model, settings, application)
    row = {
        "case_id": case["id"],
        "stage": stage,
        "trial": trial,
        "case_sha256": digest(case),
        "input_sha256": digest(payload),
        "input": payload,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "status": "ERROR",
        "failures": [],
        "review_required": [],
    }
    started = time.perf_counter()
    try:
        response = transport.json("/api/chat", payload)
        if not isinstance(response, dict):
            raise ValueError("Expected an HTTP JSON object")
        row.update(
            elapsed_seconds=time.perf_counter() - started,
            response=response,
            output_sha256=digest(response),
        )
        row.update(
            grade_application(response) if application else
            grade_model(case, response, protocol, validate_route)
        )
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        row.update(
            status="ERROR",
            elapsed_seconds=time.perf_counter() - started,
            error_type=type(error).__name__,
        )
    return row
