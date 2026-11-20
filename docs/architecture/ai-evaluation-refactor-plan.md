# AI evaluation architecture refactor plan

Status: design baseline; implementation not yet migrated. Based on draft PR #75.

## Boundaries

- **Evaluation runner (independent):** versioned scenarios, model/provider and application transports, deterministic assertions, optional human review, immutable capture provenance, comparison and reporting. It must not import chat application source or require Java to execute generic tests.
- **Application adapter (optional):** public HTTP contract for end-to-end requests. A test-only, opt-in diagnostic event export may expose structured flow events correlated to a request, without exposing personal readings, prompts or credentials by default.
- **Chat-internal tests:** Java unit/integration tests for route validation, strict decoding, tool policy, SQL constraints and error paths. They stay with the backend.
- **Compatibility adapter:** the existing Java EvaluationBridge in PR #75 can remain temporarily for production prompt/schema parity and deterministic validation; do not mistake this white-box bridge for an independent E2E runner.

## Scenario expectations

Each case declares input, expected final behavior, and optional flow expectations (route, metric, aggregation, period, number of model calls, SQL execution count, rejection reason). A flow expectation is evaluated only when its declared telemetry is available; missing telemetry must not silently pass. Route selection is not equivalent to tool invocation: support both kinds of traces without assuming the chat always uses model tool calls.

## Implementation sequence

1. Inventory PR #75 Python modules, Java bridge, fixtures, CLI, Make and CI contracts; record existing baseline and tests.
2. Introduce a standalone top-level evaluation package/module with no dependency on garmin-chat implementation; preserve CLI and existing case IDs.
3. Define versioned scenario, run, evidence and diagnostic-event schemas. Keep public API transport and provider-direct transport separate.
4. Implement a diagnostic reader/adapter for correlated structured events. Prefer existing safe event logging; any new diagnostic endpoint must be explicitly enabled only in isolated test environments and avoid sensitive payloads.
5. Keep or migrate Java bridge checks into chat-internal tests; retain an optional compatibility path until parity is verified.
6. Preserve deterministic oracle, manual review status, privacy rules, immutable captures, model provenance and comparison compatibility.
7. Update Make/CI/docs; run Python and Java tests, synthetic HTTP E2E and optional real-model smoke checks. Never label mocked tests as real inference.

## Acceptance criteria

- Generic suite runs independently from chat Java classes and can compare multiple model/configuration runs against the same immutable cases.
- API E2E checks final responses; optional flow checks use stable observable events, not implementation imports.
- Missing diagnostic evidence is reported as unavailable/error, never PASS.
- Existing PR #75 cases, grading semantics and privacy constraints remain intact unless a documented versioned migration changes them.
- No production diagnostic exposure by default; no personal Garmin evidence committed.
- Full suite passes before declaring refactor complete.
