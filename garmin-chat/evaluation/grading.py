"""Exact contract checks plus hash-bound human adjudication; no keyword-based semantic PASS."""

import json
import re

RUBRIC_CHECKS = {"factual_accuracy", "coverage", "relevance", "no_unsupported_claims"}


def route_grade(case, decoded, text):
    if not decoded.get("accepted"):
        return "FAIL", ["production_validation_rejected"], []
    expected = case["expected"]
    if decoded.get("route") != expected["route"]:
        return "FAIL", ["wrong_route"], []
    raw = json.loads(text)
    if expected["route"] == "ANALYZE":
        actual, wanted = decoded["selection"], expected["selection"]
        if (
            sorted(actual["metrics"]) != sorted(wanted["metrics"])
            or actual["aggregation"] != wanted["aggregation"]
        ):
            return "FAIL", ["wrong_metric_or_calculation"], []
        period = actual["period"]
        normalized = {k: v for k, v in period.items() if v not in (None, "", 0)}
        if normalized != wanted["period"]:
            return "FAIL", ["wrong_period"], []
        return "PASS", [], []
    if expected["route"] == "UNSUPPORTED":
        return (
            ("PASS", [], [])
            if decoded.get("reason") == expected["reason"]
            else ("FAIL", ["wrong_reason"], [])
        )
    allowed = expected["target"]
    if raw.get("target") not in (allowed if isinstance(allowed, list) else [allowed]):
        return "FAIL", ["wrong_clarification_target"], []
    return "NEEDS_REVIEW", [], ["focused_clarification_preserves_supplied_information"]


def initial_grade(case, text, decoded=None):
    if not isinstance(text, str) or not text.strip():
        return "FAIL", ["empty_answer"], []
    if case["stage"] == "routing":
        return route_grade(case, decoded, text)
    # Numeric presence is only a review aid: a correct number can occur in a false claim,
    # and a valid answer may spell it out in words. It never produces a semantic verdict.
    return "NEEDS_REVIEW", [], case["review_checks"]


def review_key(row):
    return f"{row['stage']}/{row['case_id']}/{row['trial']}"


def apply_review(row, annotation):
    required = set(row["review_required"])
    if annotation is None or not required:
        return row
    if (
        annotation.get("input_sha256") != row["input_sha256"]
        or annotation.get("output_sha256") != row["output_sha256"]
    ):
        raise ValueError("Review hashes do not match captured input/output")
    if (
        not annotation.get("reviewer", "").strip()
        or not annotation.get("rationale", "").strip()
    ):
        raise ValueError("Review needs reviewer and rationale")
    checks = annotation.get("checks", {})
    if set(checks) != required or any(
        v not in {"PASS", "FAIL", "NEEDS_REVIEW"} for v in checks.values()
    ):
        raise ValueError("Review must grade exactly the required rubric checks")
    result = dict(row, review=annotation)
    if row["status"] in {"FAIL", "ERROR", "SKIP"}:
        return result
    result["status"] = (
        "FAIL"
        if "FAIL" in checks.values()
        else "NEEDS_REVIEW"
        if "NEEDS_REVIEW" in checks.values()
        else "PASS"
    )
    result["failures"] = [name for name, grade in checks.items() if grade == "FAIL"]
    return result


def numeric_hints(text):
    return re.findall(r"(?<!\w)-?\d+(?:[,.]\d+)*", text)
