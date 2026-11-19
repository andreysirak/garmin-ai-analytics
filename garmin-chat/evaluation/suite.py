"""Versioned suite loading and stable fingerprints."""

import hashlib
import json
from pathlib import Path
from fixtures import evidence

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def digest(value):
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode()
    return hashlib.sha256(encoded).hexdigest()


def load_suite(path=None):
    suite = json.loads(Path(path or ROOT / "cases.json").read_text())
    ids = [case["id"] for case in suite["cases"]]
    if (
        not ids
        or any(not isinstance(i, str) or not i.strip() for i in ids)
        or len(ids) != len(set(ids))
    ):
        raise ValueError("Case IDs must be nonempty and unique")
    for case in suite["cases"]:
        if case["split"] not in {"regression", "holdout"}:
            raise ValueError("Invalid case split")
        if case["stage"] == "explanation":
            evidence(case, suite["protocol"])
        elif case["stage"] != "routing":
            raise ValueError("Unknown stage")
    return suite


def input_item(case, protocol):
    item = {"question": case["question"]}
    if case["stage"] == "explanation":
        item["evidence"] = evidence(case, protocol)
    return item


def production_fingerprint():
    paths = list((REPO / "garmin-chat/backend/src/main").rglob("*"))
    paths += [
        REPO / "garmin-chat/backend/pom.xml",
        REPO
        / "garmin-chat/backend/src/test/java/com/garmin/analytics/chat/evaluation/EvaluationBridge.java",
    ]
    return digest(
        {
            str(p.relative_to(REPO)): digest(p.read_text())
            for p in sorted(paths)
            if p.is_file()
        }
    )
