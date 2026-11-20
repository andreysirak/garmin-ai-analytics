"""Capture repeated isolated inference or application HTTP trials."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess

from grading import review_key
from reporting import describe, save, save_summary
from ollama import inspect_model, identity_unchanged
from suite import ROOT, REPO, digest, input_item, load_suite, production_fingerprint
from transport import JavaBridge, LocalHttp
from trial import execute_trial


def command(*argv):
    return subprocess.check_output(argv, cwd=REPO, text=True).strip()


def run(args):
    suite = load_suite()
    cases = [
        c
        for c in suite["cases"]
        if (args.split == "all" or c["split"] == args.split)
        and (
            args.mode == "application"
            and c["stage"] == "routing"
            or args.mode != "application"
            and (args.stage == "all" or c["stage"] == args.stage)
        )
    ]
    if not cases:
        raise ValueError("No cases selected")
    if args.mode == "application" and (
        not args.synthetic_database or not args.deployment_id
    ):
        raise ValueError(
            "Application mode requires --synthetic-database and --deployment-id; never point at a personal deployment"
        )
    bridge = JavaBridge()
    items = [input_item(c, suite["protocol"]) for c in cases]
    exported = bridge.call("export", items, suite["protocol"])
    settings = {
        "temperature": 0,
        "num_ctx": suite["protocol"]["context_tokens"],
        "num_predict": suite["protocol"]["response_tokens"],
        "num_gpu": 0,
    }
    runtime = LocalHttp(args.ollama_url, args.timeout)
    version, model, show = inspect_model(runtime, args.model)
    dirty = command("git", "status", "--porcelain") != ""
    plan = {
        "suite_version": suite["version"],
        "rubric_version": suite["rubric_version"],
        "cases": cases,
        "protocol": suite["protocol"],
        "split": args.split,
        "mode": args.mode,
        "repetitions": args.repetitions,
        "grader_sha256": digest((ROOT / "grading.py").read_text()),
    }
    manifest = {
        "format_version": 1,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "protocol_sha256": digest(plan),
        "plan": plan,
        "source_sha": command("git", "rev-parse", "HEAD"),
        "source_dirty": dirty,
        "production_sha256": production_fingerprint(),
        "model": model,
        "settings": settings,
        "runtime": version,
        "unreported_sampling_defaults": "not overridden; not fully exposed",
        "deployment_id": args.deployment_id if args.mode == "application" else None,
        "measurement": "Application HTTP includes live model and SQL; no exact evidence captured"
        if args.mode == "application"
        else "Isolated provider HTTP excludes SQL and Java orchestration",
        "runner_sha256": digest({p.name: p.read_text() for p in ROOT.glob("*.py")}),
    }
    out = Path(args.output).resolve()
    private_root = (REPO / "data/evaluations").resolve()
    if not out.is_relative_to(private_root) or out == private_root:
        raise ValueError(
            "Output must be a new subdirectory of Git-ignored data/evaluations"
        )
    private_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    out.mkdir(mode=0o700)
    save(out / "manifest.json", manifest)
    save(out / "model-info.json", show)
    save(out / "inputs.json", exported)
    application = (
        LocalHttp(args.application_url, args.timeout)
        if args.mode == "application"
        else None
    )
    captured = []
    for case, prompt in zip(cases, exported):
        stage = "application" if application else case["stage"]
        for trial in range(1, args.repetitions + 1):
            row = execute_trial(
                case, prompt, trial, args.model, settings, suite["protocol"],
                application if application else runtime,
                validate_route=lambda answer: bridge.call(
                    "validate", [{"output": answer}], suite["protocol"]
                )[0],
                application=bool(application),
            )
            captured.append(row)
            # Flush each result immediately; an interrupted run retains completed trials.
            with open(
                out / "results.jsonl",
                "a",
                encoding="utf-8",
                opener=lambda p, f: os.open(p, f, 0o600),
            ) as stream:
                stream.write(json.dumps(row, allow_nan=False) + "\n")
            print(f"{stage}/{case['id']}/{trial}: {row['status']}")
    # Detect external model replacement during a run; never mutate or pull models.
    manifest["model_unchanged"] = identity_unchanged(runtime, model)
    manifest["completed_at"] = datetime.now(timezone.utc).isoformat()
    save(
        out / "completion.json",
        {
            "model_unchanged": manifest["model_unchanged"],
            "completed_at": manifest["completed_at"],
            "trial_count": len(captured),
        },
    )
    save(out / "summary.json", describe(captured))
    save_summary(out / "summary.md", describe(captured))
    save(
        out / "review-template.json",
        {
            review_key(r): {
                "input_sha256": r["input_sha256"],
                "output_sha256": r["output_sha256"],
                "reviewer": "",
                "rationale": "",
                "checks": dict.fromkeys(r["review_required"], "NEEDS_REVIEW"),
            }
            for r in captured
            if r["review_required"]
        },
    )
    print("Private results:", out)
    return (
        0
        if all(r["status"] == "PASS" for r in captured) and manifest["model_unchanged"]
        else 2
    )
