# Java AI Evaluation Framework — migration in progress

This standalone Maven module is the **Java 25 replacement target** for the
existing Python framework in `../evaluation`. It deliberately has **no**
dependency on Garmin Chat, Spring Boot or Spring AI.

## Architecture boundary

- `ChatTransport` is an HTTP port; `HttpChatTransport` is the loopback-only
  application adapter.
- `EvaluationSuite` reads the **existing 23 JSON scenarios**. The fixtures
  are shared during migration; no duplicate case catalog is maintained.
- `ApplicationEvaluator` distinguishes invalid answers (`FAIL`) from
  answers needing factual and semantic review (`NEEDS_REVIEW`). A nonempty
  response never earns an automatic `PASS`.
- Garmin Chat component and AI orchestration tests stay in
  `backend/src/test/java`. They may use Spring AI and backend internals.
- Future model adapters and optional component adapters must not introduce
  Spring AI or Garmin Chat into the independent core.

## Commands

Run from `garmin-chat/evaluation-java` with JDK 25 and Maven:

```bash
mvn test
mvn exec:java -Dexec.args="list --cases ../evaluation/cases.json"
```

Application mode requires a running, **isolated synthetic** Garmin Chat
deployment. The flag is an operator assertion, **not database verification**.

```bash
mvn exec:java -Dexec.args="application --cases ../evaluation/cases.json --synthetic-database yes --deployment-id isolated-local --application-url http://127.0.0.1:8081 --output ../../data/evaluations/java-smoke-001"
```

Use a fresh output directory every time. Do not send real Garmin health data
to the evaluation suite.

## Migration acceptance gate — NOT YET MET

This is an **initial Java foundation, not a feature-complete migration**.
The existing Python implementation remains the authoritative runner until the
following have been implemented and verified in Java:

- model-only routing and explanation evaluation, Ollama adapter and optional
  component prompt/validator adapter
- exact evidence fixtures, deterministic routing checks, review annotations
  bound to input/output hashes, immutable capture provenance
- baseline comparison, missing/added/regressed-case detection, detailed reports
- equivalence tests against existing cases and failure modes, privacy and
  isolation checks, and end-to-end local test runs

The Java application prototype currently writes `results.jsonl` and a minimal
`summary.json` only. **Its output is not yet compatible with Python
review/compare capture validation**. The old Make targets have deliberately
not been replaced. Do not merge or delete Python until parity is demonstrated.
