#!/usr/bin/env python3
"""Opt-in evaluation CLI. Offline discovery/self-tests never contact a model or database."""

import argparse
import json
from pathlib import Path
import subprocess
import sys

from capture import run
from comparison import compare, verify_capture
from grading import apply_review, review_key
from reporting import describe, read, rows, save
from suite import REPO, input_item, load_suite, production_fingerprint
from component_bridge import JavaBridge


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser(
        "prepare",
        help="Verify freshly compiled prompt export and record a source fingerprint",
    )
    sub.add_parser("list", help="Validate and list cases; no Java or network required")
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--mode", choices=["model", "application"], default="model")
    run_parser.add_argument(
        "--stage", choices=["routing", "explanation", "all"], default="all"
    )
    run_parser.add_argument(
        "--split", choices=["regression", "holdout", "all"], default="regression"
    )
    run_parser.add_argument("--repetitions", type=int, choices=range(1, 21), default=3)
    run_parser.add_argument("--model", default="qwen2.5:1.5b-instruct-q4_K_M")
    run_parser.add_argument("--ollama-url", default="http://127.0.0.1:11434")
    run_parser.add_argument("--application-url", default="http://127.0.0.1:8081")
    run_parser.add_argument("--synthetic-database", action="store_true")
    run_parser.add_argument("--deployment-id")
    run_parser.add_argument("--timeout", type=float, default=120)
    run_parser.add_argument("--output", required=True)
    review = sub.add_parser("review")
    review.add_argument("run_dir", type=Path)
    review.add_argument("annotations", type=Path)
    review.add_argument("--output", required=True, type=Path)
    comparison = sub.add_parser("compare")
    comparison.add_argument("baseline", type=Path)
    comparison.add_argument("candidate", type=Path)
    comparison.add_argument("--baseline-results", default="results.jsonl")
    comparison.add_argument("--candidate-results", default="results.jsonl")
    comparison.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    if args.command == "prepare":
        suite = load_suite()
        JavaBridge(require_stamp=False).call(
            "export",
            [input_item(c, suite["protocol"]) for c in suite["cases"]],
            suite["protocol"],
        )
        stamp = REPO / "garmin-chat/backend/target/evaluation-prepared.json"
        stamp.write_text(json.dumps({"production_sha256": production_fingerprint()}))
        stamp.chmod(0o600)
        print(
            "Verified production prompt export; prepared source fingerprint recorded."
        )
        return 0
    if args.command == "list":
        suite = load_suite()
        for case in suite["cases"]:
            print(case["id"], case["stage"], case["split"], case["question"])
        return 0
    if args.command == "run":
        return run(args)
    if args.command == "review":
        data = rows(args.run_dir / "results.jsonl")
        verify_capture(
            read(args.run_dir / "manifest.json"),
            read(args.run_dir / "completion.json"),
            data,
        )
        annotations = read(args.annotations)
        known = {review_key(row) for row in data if row["review_required"]}
        if set(annotations) - known:
            raise ValueError("Annotation references unknown or non-reviewable trial")
        judged = [apply_review(row, annotations.get(review_key(row))) for row in data]
        save(args.output, judged, lines=True)
        print(json.dumps(describe(judged), indent=2))
        return 0 if all(row["status"] == "PASS" for row in judged) else 2
    for folder in [args.baseline, args.candidate]:
        completion = read(folder / "completion.json")
        manifest = read(folder / "manifest.json")
        if manifest["plan"]["mode"] == "model" and not completion.get("model_unchanged"):
            raise ValueError("Cannot compare model runs with missing/changed model identity")
    before, after = (
        rows(args.baseline / args.baseline_results),
        rows(args.candidate / args.candidate_results),
    )
    for folder, data in [(args.baseline, before), (args.candidate, after)]:
        verify_capture(
            read(folder / "manifest.json"), read(folder / "completion.json"), data
        )
    result = compare(
        read(args.baseline / "manifest.json"),
        before,
        read(args.candidate / "manifest.json"),
        after,
    )
    save(args.output, result)
    print(json.dumps(result, indent=2))
    return (
        2
        if any(result[k] for k in ("regressions", "missing", "added", "inconclusive"))
        else 0
    )


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Evaluation stopped: {error}", file=sys.stderr)
        sys.exit(1)
