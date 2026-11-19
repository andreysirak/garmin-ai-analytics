"""Capture repeated isolated inference or application HTTP trials."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time

from fixtures import evidence
from grading import initial_grade, numeric_hints, review_key
from reporting import describe, save, save_summary
from suite import ROOT, REPO, digest, input_item, load_suite, production_fingerprint
from transport import JavaBridge, LocalHttp


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
    version = runtime.json("/api/version")
    tags = runtime.json("/api/tags")
    found = [
        m
        for m in tags.get("models", [])
        if args.model in {m.get("name"), m.get("model")}
    ]
    if len(found) != 1 or not found[0].get("digest"):
        raise ValueError(
            "Requested model must already be installed and have a digest; no auto-pull"
        )
    model = {"name": args.model, "digest": found[0]["digest"]}
    show = runtime.json("/api/show", {"model": args.model})
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
            payload = (
                {"message": case["question"]}
                if application
                else {
                    "model": args.model,
                    **prompt,
                    "stream": False,
                    "options": settings,
                }
            )
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
                response = (
                    application.json("/api/chat", payload)
                    if application
                    else runtime.json("/api/chat", payload)
                )
                if not isinstance(response, dict):
                    raise ValueError("Expected an HTTP JSON object")
                row.update(
                    elapsed_seconds=time.perf_counter() - started,
                    response=response,
                    output_sha256=digest(response),
                )
                if application:
                    text = response.get("answer")
                    if not isinstance(text, str) or not text.strip():
                        row.update(
                            status="FAIL", failures=["empty_or_invalid_http_answer"]
                        )
                    else:
                        row.update(
                            status="NEEDS_REVIEW",
                            review_required=[
                                "routing_behavior",
                                "factual_accuracy",
                                "coverage",
                                "relevance",
                                "no_unsupported_claims",
                            ],
                        )
                        row["limitation"] = (
                            "HTTP text alone cannot establish route, SQL or exact evidence; attach a reviewed synthetic trace"
                        )
                else:
                    if not isinstance(response.get("message"), dict):
                        raise ValueError("Missing provider message object")
                    text = response["message"].get("content", "")
                    if (
                        not response.get("done")
                        or response.get("done_reason") == "length"
                        or response.get("message", {}).get("tool_calls")
                    ):
                        row.update(
                            status="FAIL",
                            failures=["incomplete_or_unexpected_tool_output"],
                        )
                    else:
                        decoded = None
                        if stage == "routing":
                            decoded = bridge.call(
                                "validate", [{"output": text}], suite["protocol"]
                            )[0]
                            row["production_validation"] = decoded
                        status, failures, required = initial_grade(case, text, decoded)
                        row.update(
                            status=status, failures=failures, review_required=required
                        )
                        if stage == "explanation":
                            row.update(
                                evidence=evidence(case, suite["protocol"]),
                                numeric_hints=numeric_hints(text)
                                if isinstance(text, str)
                                else [],
                            )
            except (OSError, ValueError, RuntimeError, KeyError) as error:
                # Preserve prior trials and keep operational errors distinct from model quality failures.
                row.update(
                    status="ERROR",
                    elapsed_seconds=time.perf_counter() - started,
                    error_type=type(error).__name__,
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
    try:
        final = runtime.json("/api/tags")
        manifest["model_unchanged"] = any(
            m.get("digest") == model["digest"]
            and args.model in {m.get("name"), m.get("model")}
            for m in final.get("models", [])
        )
    except (OSError, ValueError):
        manifest["model_unchanged"] = False
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
