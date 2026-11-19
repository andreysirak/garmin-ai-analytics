"""Compare the same task/trial protocol, never hide missing cases or unreviewed answers."""

from collections import Counter

STATUSES = {"PASS", "FAIL", "ERROR", "SKIP", "NEEDS_REVIEW"}


def index(rows):
    output = {}
    for row in rows:
        key = (row["stage"], row["case_id"], row["trial"])
        if key in output or row["status"] not in STATUSES:
            raise ValueError("Duplicate result or unknown status")
        output[key] = row
    return output


def compare(baseline_manifest, baseline, candidate_manifest, candidate):
    # Model, prompts and source may change: they are experimental factors, not case changes.
    if baseline_manifest["protocol_sha256"] != candidate_manifest["protocol_sha256"]:
        raise ValueError(
            "Incompatible cases/rubric/protocol/stages/repetitions; create a new baseline"
        )
    before, after = index(baseline), index(candidate)
    result = {
        "regressions": [],
        "improvements": [],
        "inconclusive": [],
        "missing": [],
        "added": [],
        "changed_inputs": [],
        "unchanged": 0,
    }
    for key in sorted(before.keys() | after.keys()):
        label = "/".join(map(str, key))
        if key not in after:
            result["missing"].append(label)
        elif key not in before:
            result["added"].append(label)
        else:
            a, b = before[key]["status"], after[key]["status"]
            if before[key]["input_sha256"] != after[key]["input_sha256"]:
                result["changed_inputs"].append(label)
            if a not in {"PASS", "FAIL"} or b not in {"PASS", "FAIL"}:
                result["inconclusive"].append(label)
            elif a == "PASS" and b == "FAIL":
                result["regressions"].append(label)
            elif a == "FAIL" and b == "PASS":
                result["improvements"].append(label)
            else:
                result["unchanged"] += 1
    result["baseline_counts"] = dict(Counter(row["status"] for row in baseline))
    result["candidate_counts"] = dict(Counter(row["status"] for row in candidate))
    result["factors_changed"] = [
        key
        for key in (
            "source_sha",
            "source_dirty",
            "production_sha256",
            "model",
            "settings",
            "runtime",
        )
        if baseline_manifest.get(key) != candidate_manifest.get(key)
    ]
    result["multiple_recorded_factors_changed"] = len(result["factors_changed"]) > 1
    result["note"] = (
        "Descriptive trial transitions; not statistical significance or a release approval."
    )
    return result


def verify_capture(manifest, completion, rows):
    """Reject incomplete/edited captures before review or baseline comparison."""
    from suite import digest

    plan = manifest["plan"]
    if manifest["protocol_sha256"] != digest(plan):
        raise ValueError("Manifest protocol hash mismatch")
    cases = {case["id"]: case for case in plan["cases"]}
    expected = {
        (
            ("application" if plan["mode"] == "application" else case["stage"]),
            case["id"],
            trial,
        )
        for case in cases.values()
        for trial in range(1, plan["repetitions"] + 1)
    }
    actual = index(rows)
    if set(actual) != expected or completion["trial_count"] != len(expected):
        raise ValueError("Incomplete or unexpected capture trials")
    for row in rows:
        if row["case_sha256"] != digest(cases[row["case_id"]]) or row[
            "input_sha256"
        ] != digest(row["input"]):
            raise ValueError("Captured case/input hash mismatch")
        if "response" in row and row["output_sha256"] != digest(row["response"]):
            raise ValueError("Captured response hash mismatch")
