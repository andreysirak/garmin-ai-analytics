# Evaluation technical reference

[Start guide](README.md) · [Test-case catalogue](TEST-CASES.md)

## Boundaries

| Layer | Purpose | Execution |
| --- | --- | --- |
| Standalone unit tests | Dataset, arithmetic oracle, deterministic graders, review and comparison | `make chat-eval-test` |
| Standalone adapter tests | Public HTTP request/response, simulated Ollama, capture workflow and transport bounds | `make chat-eval-test` |
| Backend component tests | Production prompts and schema, strict routing, date/metric validation and advisors | `make chat-eval-component-test` |
| Backend AI flow integration | Controller, routing, synthetic SQL evidence and explanation with fake model responses | `make chat-eval-component-test` |
| Black-box E2E | Public chatbot HTTP on an isolated synthetic deployment | `make chat-eval-application` |
| Model evaluation | Versioned prompt and evidence fixtures sent directly to an installed Ollama model | `make chat-eval-model` |
| AI quality evaluation | Exact deterministic checks and explicit human adjudication of prose | Capture, then `review` and `compare` |

The single Maven module lives in `ai-evaluation-framework`. Its `core` runs trials
against exchange and grading contracts. The `dataset` bundles versioned cases
and prompts and calculates synthetic evidence. The `transport` package provides
bounded loopback HTTP and a public chat adapter. The `model` provider contract
handles model requests, normalized completions and provenance, with Ollama as
the first implementation. The `evaluator` checks the independent routing
contract and determines required review. The `review` and `report` packages
adjudicate and compare immutable captures. The `cli` wires them together.

The v1 standalone prompt fixtures were frozen from the former production
export and are maintained independently. The optional production contract test
compares the frozen system text and schema with the backend and checks the
actual router with a substitute model. Component and AI flow tests live under
`garmin-chat/backend/src/test/java` and may use Spring AI. The standalone runner consumes
only its packaged resources and the selected endpoint.

## Cases and quality rules

The reference date is November 19, 2026 in Europe/Oslo. Last calendar week
covers November 9–15, and the seven completed days before today cover
November 12–18. Explanation fixtures cover November 9–15. The independent
decimal oracle verifies each frozen expected value before inference and accounts
for zero readings, missing summaries/fields, nulls and invalid types.

Routing first checks strict JSON and route-specific fields, the metric and
calculation allowlist, and valid supported calendar ranges. It then checks the
exact route, selection or reason. Clarifications require an allowed target and
human review of focus and whether the wording preserves information already
given by the user. Explanations always require review, with numeric tokens
available as aids.

1. **Factual accuracy** checks calculation, values and units against evidence.
   Reasonable rounding is allowed. Distinguish unknown from zero and averages
   from totals.
2. **Coverage** checks numeric days out of the expected days and applicable
   gaps. An incomplete sum must be labelled partial.
3. **Relevance** checks whether the answer addresses the question or explains
   why available evidence cannot answer it.
4. **Unsupported claims** checks for invented readings, targets, groups or
   relationships. Steps do not measure work productivity and recorded duration
   does not establish sleep quality.

Application answers also require review of routing behavior. The observable
answer and known synthetic inputs are the baseline evidence. A reviewer can
cite a separately obtained synthetic trace in the rationale when needed.
Ordinary application runs use only the public API. This migration includes no
trace ingestion or LLM judge.

| Status | Meaning |
| --- | --- |
| `PASS` | Required deterministic checks passed or the human adjudication approved all required checks. |
| `FAIL` | A deterministic or reviewed check failed. |
| `ERROR` | Transport, timeout, HTTP or provider response protocol failure. Error types are captured separately. |
| `SKIP` | A check was not executed. Reports and comparison treat this as inconclusive. |
| `NEEDS_REVIEW` | Required human adjudication remains pending. |

Review every acceptance trial before release. Pay particular attention to
unsupported claims, unknown-to-zero errors and numeric or coverage
contradictions. Timing quantiles and three repetitions are descriptive.
Public holdouts cease to be unseen when used for tuning.

## Capture and comparison

The runner accepts bare HTTP loopback origins, disables redirects and proxies,
limits response bodies to 8 MiB, and applies a bounded deadline. It never pulls
or modifies models. It records the Ollama version, installed tag digest,
metadata and effective request settings. Other sampling defaults come from the
provider. Temperature zero does not guarantee identical results.

Captures require a new directory under the Git-ignored `data/evaluations/`
root. Capture directories use POSIX mode 0700, and generated files mode 0600.
Use a POSIX filesystem. These are local access controls. Keep the directory
private and avoid publishing captures.

The output contains `manifest.json`, exact `inputs.json`, the trial ledger
`results.jsonl`, Ollama `model-info.json` when applicable, `summary.json`,
`summary.md`, `review-template.json` and `completion.json`. The manifest
identifies the case/rubric/protocol, grader and runner fingerprints, Java
version and Git revision/dirty flag when Git metadata is available. Model
captures identify the exact prompt fixture; application captures identify the
attested synthetic deployment. The runner flushes each trial to the ledger.
Completion binds the manifest, inputs, raw ledger and optional model metadata
to file hashes. An interrupted run lacks a valid completion record.

Annotations are keyed by stage/case/trial and bound to the input/output hashes.
Required checks must match the trial rubric exactly. The review command writes a
new result ledger. Comparison verifies every raw trial and re-applies any
embedded annotation to confirm the reviewed ledger. Hashes detect drift and
editing against the completion record. They are not a signature or proof of
who created a capture.

Comparison requires identical selected scenarios, protocol, rubric, grader and
repetitions, and stable within-run model identity. Prompts, model, settings and
source are reported as experimental factors and may vary between runs. Use the
same Java build/toolchain for controlled comparisons. `PASS → FAIL` is a
regression. `FAIL → PASS` is an improvement. Any transition involving a skip,
error or pending review is inconclusive. Compare also lists changed inputs and
counts by status. Median and p95 timing describe HTTP wall times, excluding
operational errors and skips.

## SQL and application path

`make chat-eval-sql-test` runs the existing PostgreSQL integration checks for
all 15 supported metric/operation pairs, coverage and the configured reader.
Supply the existing `GARMIN_CHAT_TEST_DB_*` environment variables for an
isolated database. The target requires an explicit URL. The tests use synthetic
CTEs and do not write personal records.

The application runner neither seeds the synthetic database nor sets the
deployment clock or model. Align the deployment with the frozen reference
period and record its configuration independently. A successful HTTP exchange
alone cannot establish route, SQL behavior or factual accuracy.

The [historical controlled evaluation](../docs/evaluations/2026-11-19-garmin-chat/README.md)
remains available as context. Use a fresh Java capture for a format v2 baseline.
