# Evaluation technical reference

Permanent checks for [#74](https://github.com/andreysirak/garmin-ai-analytics/issues/74),
before workflow changes in [#66](https://github.com/andreysirak/garmin-ai-analytics/issues/66)
and [PR #73](https://github.com/andreysirak/garmin-ai-analytics/pull/73).
No production flow, prompts, SQL, model defaults or deployments change here.

Back to the [start guide](README.md) or [test-case catalogue](TEST-CASES.md).

## Test plan

| Layer | Purpose | Execution |
| --- | --- | --- |
| Offline contracts | Cases, independent fixture arithmetic, graders, comparisons, production prompt export and routing validation | `make chat-eval-test`; CI on each PR |
| SQL integration | Existing production SQL with synthetic CTE rows; null/zero, coverage, all 15 approved operations and reader constraints | `make chat-eval-sql-test`, opt-in isolated PostgreSQL |
| Model evaluation | Real Ollama routing and explanations in isolation, frozen inputs and repeat trials | `make chat-eval-model`, opt-in |
| Application path | Existing `/api/chat` through routing, SQL and explanation, on an isolated synthetic deployment | `make chat-eval-application`, opt-in |

Offline tests with a substituted model response verify plumbing, not model quality.
Application HTTP is not the Garmin ingestion/Airflow end-to-end test. No Garmin
API, personal token, ingestion DAG or personal database is needed by this suite.

### Scope and cases

`cases.json` contains stable IDs, protocol v1.0 and rubric v1.0:

- 8 regression routing cases: supported scalar selections, calendar vs rolling
  week, missing period, ambiguity, unsupported quality and relationships.
- 12 explanation cases: complete data, partial sums/averages, real zero,
  missing summaries/fields, nulls, invalid types, unsupported conclusions.
- 3 reserved holdout routing cases, excluded by default. They are public and
  cannot be claimed as genuinely unseen once used for prompt/model tuning.

The reference clock is frozen at 2026-11-19 in Europe/Oslo. Calendar last week
is November 9–15; the past seven completed days are November 12–18. Explanation
fixtures cover November 9–15. Production Java resolves routing dates. Python's
Decimal oracle independently calculates evidence from synthetic observations
and verifies the frozen expected value before any network call.

Case IDs describe stable scenarios. Add a new case for each bug; change an
expectation deliberately when a capability changes. Review allowed meanings,
not a single prescribed sentence. Do not weaken criteria to make a model pass.
Keep tuning cases and reserved acceptance cases separate. Add multilingual,
paraphrase, boundary-date, timeout and security scenarios as features grow.

### Grading and acceptance

Statuses are `PASS`, `FAIL`, `ERROR`, `NEEDS_REVIEW` (and `SKIP` understood by the
report comparator). An unavailable service is an error, not a bad-model answer
or a green skipped quality check. Offline suites may skip the optional SQL tests;
report their separate outcome explicitly.

**Routing:** the test-only Java bridge calls the real `GarminRequestRouter`,
including strict parsing, route consistency, catalog allowlists and date
validation. A substituted ChatModel supplies only the captured raw response;
no SQL or real inference occurs during replay. A syntactically accepted wrong
route, metric, operation or period fails independently of Java acceptance.
Clarification also needs review of whether its wording is focused and preserves
information supplied by the user. Reason codes can be checked exactly.

**Explanation:** exact evidence and response are retained. Numeric tokens are
review hints only: finding the expected number does not establish truth. Free
text stays `NEEDS_REVIEW` until these checks are adjudicated:

1. `factual_accuracy`: values/calculation/units agree with evidence; reasonable
   rounding is allowed. Unknown is not zero; averages are not totals.
2. `coverage`: incomplete evidence reports numeric days out of expected days and
   applicable gaps; an incomplete sum is labelled partial. Zero is valid.
3. `relevance`: answers the actual question or states why evidence cannot do so.
4. `no_unsupported_claims`: no invented targets/groups, productivity proxy,
   sleep-quality substitution, relationship or causal conclusion.

Full ISO dates in prose are a separate presentation policy here, not a core
failure when dates are already presented elsewhere. That is an explicit change
from the old prompt-strict grading; historical percentages are not comparable.

Use human review first. A calibrated external evaluator may later propose
annotations using this same ledger, but must identify itself and remain
reviewable; no LLM judge is installed or silently used. Spring AI evaluators do
not prove numerical correctness. Assertions and the reviewer can both be wrong.

**Release gate:** no unreviewed critical cases; no unsupported analytical claims,
unknown-to-zero errors or numeric/coverage contradictions in the acceptance set;
all required deterministic checks pass. Inspect every regression and all trials,
not just a pooled percentage. Three repetitions are an initial diagnostic
setting, not evidence of production reliability. Agree a larger acceptance run
before declaring the demo reliable. Timing quantiles are descriptive and are
not load-test results. Existing known model failures remain failures, not xfails.

## Commands

Python 3.12+ and Java 25/Maven are required. Python uses only the standard library.
Run commands from the repository root. Do not paste credentials into command lines.

```bash
make chat-eval-test
python3 garmin-chat/evaluation/run.py list
make chat-eval-prepare
```

The last command compiles the test-only Java adapter and writes its dependency
classpath inside Maven's ignored `target/`. It downloads normal Maven build
plugins/dependencies as needed; it does not pull models or connect to databases.
Prepare verifies all prompt exports and records a source fingerprint. Runs reject
a stale fingerprint after production/adapter source changes; rerun prepare.

### Real model: opt-in

Keep Ollama already running with an installed model. Forward its Service to a
loopback port when needed. The runner accepts only loopback origins, disables
proxies/redirects, never auto-pulls a model and does not change server settings.
The model/context settings are explicit request options, not a deployment change.

```bash
python3 garmin-chat/evaluation/run.py run \
  --ollama-url http://127.0.0.1:11434 \
  --output data/evaluations/baseline-001
```

Default: regression cases only, both stages, three trials each (60 model calls).
Use `--stage routing` / `--stage explanation`, `--split holdout` or `--split all`
explicitly. Compare a different already-installed model with `--model TAG` on
unchanged cases. Context 32768, output 1024, temperature 0, CPU are pinned in the
protocol; other server sampling defaults are not fully exposed or pinned.
Temperature zero does not promise identical outputs. Freeze runtime/hardware and
other workload for comparisons. Isolate one factor at a time where practical.

Outputs must be a **new** subdirectory of Git-ignored `data/evaluations/`.
Existing captures are never overwritten. Each trial is flushed immediately;
an interrupted run lacks `completion.json` and cannot be compared as complete.
Directories use mode 0700 and generated files mode 0600 (local access controls,
not encryption). Do not commit or automatically upload raw reports.

A run includes `manifest.json`, exact `inputs.json`, model metadata,
`results.jsonl`, `completion.json`, `summary.json`, `summary.md` and `review-template.json`.
Records capture requests/responses, hashes, production validation, timing and
provider token metadata. Source revision/dirty flag, production fingerprint,
runner fingerprint, model digest, versions and effective request settings are
recorded. Completion checks the tag still resolves to the original digest.
No historical result is labelled a new live baseline.

### Review a captured run

Copy the generated template to a separate annotations file. Fill reviewer,
rationale and every required check with `PASS`, `FAIL` or `NEEDS_REVIEW`.
Never edit captured results to make them pass. Use a durable reviewer identity;
identify AI-assisted judgment explicitly and have a person calibrate it.
Each annotation is bound to the exact input and response hashes. It cannot erase
a deterministic failure, nor be reused on a different answer.

```bash
python3 garmin-chat/evaluation/run.py review \
  data/evaluations/baseline-001 \
  data/evaluations/baseline-001/annotations.json \
  --output data/evaluations/baseline-001/reviewed.jsonl
```

Exit 0 means all trials passed; exit 2 means failures, errors or pending review
remain. Exit 1 means setup/protocol failure. Review leaves the original capture
unchanged. A synthetic fixture answer saying only “3000 steps” is not acceptable
when the evidence is a partial sum for 3 of 7 days.

### Compare with a baseline

```bash
python3 garmin-chat/evaluation/run.py compare \
  data/evaluations/baseline-001 data/evaluations/candidate-001 \
  --baseline-results reviewed.jsonl --candidate-results reviewed.jsonl \
  --output data/evaluations/comparison-001.json
```

Comparison requires identical case/rubric/protocol/stage/split/repetition hashes,
complete capture files, and unchanged model identity within each run. Prompts,
source or model may differ between runs and are reported as experimental factors.
Changing several factors prevents attributing an improvement to just one.
Case/trial transitions, missing/added results, unreviewed/error cases and input
changes are listed separately. `ERROR → PASS` is operational recovery, not a
model-quality improvement. A nonzero result prevents a green regression gate.

### SQL and application path

`make chat-eval-sql-test` reuses `PostgresMetricAggregationDatabaseTest`, including
its synthetic CTE fixtures and existing reader checks. Supply its existing
`GARMIN_CHAT_TEST_DB_*` environment variables for an isolated test database. No
DDL or personal table writes are performed by these tests. The URL is required
explicitly so the wrapper cannot silently pass with all DB tests skipped.

For a full chat-path run, first provision an **isolated deployment with only
synthetic records**, the same model tag/settings and a recorded backend revision.
The runner does not provision it, change its clock or inspect its database.
`--synthetic-database` is the operator's acknowledgement, not automatic proof of
isolation. Forward the backend and Ollama to loopback. Do not use your normal
personal deployment as a regression fixture.

```bash
python3 garmin-chat/evaluation/run.py run --mode application \
  --synthetic-database --deployment-id synthetic-backend-COMMIT \
  --application-url http://127.0.0.1:8081 \
  --output data/evaluations/application-001
```

Application mode sends only routing-case questions through `/api/chat`; it does
not inject the explanation evidence fixtures. It records HTTP results and marks
semantic agreement as `NEEDS_REVIEW`. HTTP 200 does not prove correct routing or
facts. Adjudication requires a separately captured synthetic trace/SQL evidence
and backend/model configuration. Do not approve factual accuracy without it.
The application clock is not frozen by this runner; align the test deployment
with the reference date or create a new reviewed protocol. A different period
must not be silently compared with the frozen baseline. Ollama metadata queried
by the runner does not prove the backend uses that same model/configuration;
verify the deployment independently. This first implementation intentionally
makes no automated end-to-end factual acceptance claim.

## Provenance, reuse and limitations

Production Java prompt factory, catalog, schema, decoder and validation are
reused rather than copied. SQL tests remain the existing test suite. The earlier
private one-off harness **source was not supplied**, only result files and its
description; this implementation cannot truthfully claim to reuse that source.
Existing synthetic scenarios and coverage rules informed these stable fixtures.
The [historical evaluation](../../docs/evaluations/2026-11-19-garmin-chat/README.md)
remains unchanged and is not directly comparable to this rubric.

CI also exercises capture against a synthetic loopback HTTP stub and the real
Java bridge. That is transport/validation coverage, not actual inference.
No live model run or model improvement is implied by passing runner tests.
No stronger model, automatic judge, personal trace import or Terraform/cluster
provisioner is added. General backend/frontend/deployment CI remains #71; the
new CI job covers only evaluation contracts and the Java bridge.

References and their use are explained in the [practice-to-implementation mapping](README.md#practices-and-reading).

- [Anthropic: Demystifying evals for AI agents](https://www.anthropic.com/research/demystifying-evals-for-ai-agents)
- [Spring AI: Evaluation Testing](https://docs.spring.io/spring-ai/reference/api/testing.html)
  (current documentation may use APIs newer than our pinned Spring AI version).
