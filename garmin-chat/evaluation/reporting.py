"""Immutable private artifacts and descriptive stage summaries."""

from collections import Counter, defaultdict
import json
import math
import os
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_text())


def rows(path):
    return [
        json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()
    ]


def save(path, value, lines=False):
    # Never replace a prior capture, review or comparison.
    text = (
        "".join(
            json.dumps(v, ensure_ascii=False, allow_nan=False) + "\n" for v in value
        )
        if lines
        else json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    )
    with open(
        path, "x", encoding="utf-8", opener=lambda p, f: os.open(p, f, 0o600)
    ) as stream:
        stream.write(text)


def save_summary(path, summary):
    lines = [
        "# AI evaluation summary",
        "",
        "This is a test-run report, not a release approval.",
        "",
        "| Stage | PASS | FAIL | ERROR | NEEDS_REVIEW | Median s | p95 s |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for stage, value in summary.items():
        counts = value["counts"]
        cells = [stage] + [
            str(counts.get(k, 0)) for k in ["PASS", "FAIL", "ERROR", "NEEDS_REVIEW"]
        ]
        cells += [
            str(round(value[k], 3)) if k in value else "n/a"
            for k in ["median_seconds", "p95_seconds"]
        ]
        lines.append("| " + " | ".join(cells) + " |")
    lines += [
        "",
        "Timings are per-stage HTTP wall times. Operational errors are excluded from timing quantiles.",
        "Review results.jsonl and hash-bound annotations before interpreting pending cases as successes.",
        "No result here claims statistical significance, safety, or ingestion-pipeline acceptance.",
        "",
    ]
    with open(
        path, "x", encoding="utf-8", opener=lambda p, f: os.open(p, f, 0o600)
    ) as stream:
        stream.write("\n".join(lines))


def describe(rows_):
    stages = defaultdict(list)
    for row in rows_:
        stages[row["stage"]].append(row)
    output = {}
    for stage, group in stages.items():
        timing = sorted(
            x["elapsed_seconds"]
            for x in group
            if "elapsed_seconds" in x and x["status"] not in {"ERROR", "SKIP"}
        )
        output[stage] = {"counts": dict(Counter(x["status"] for x in group))}
        if timing:
            middle = len(timing) // 2
            median = (
                timing[middle]
                if len(timing) % 2
                else (timing[middle - 1] + timing[middle]) / 2
            )
            output[stage].update(
                median_seconds=median,
                p95_seconds=timing[math.ceil(0.95 * len(timing)) - 1],
            )
    return output
