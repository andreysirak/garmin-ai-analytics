# Chat diagnostics and local model evaluation (#66)

These diagnostics cover routing, server-controlled analytics and evidence explanation. Live Ollama and
SQL-backed evaluation is still required on the owner's local cluster.

## Normal operation

Each request gets a generated `request_id` in logging MDC, shared across
the HTTP thread and the conversation worker. Follow it through `chat_started`,
`model_started` and `model_completed` for each turn, `sql_completed`,
`tool_execution` and `chat_completed`. Tool events report the registered name,
outcome and duration. `chat_routing` logs the proposed route, validation
outcome and routing duration. `chat_route_selected` logs the effective route.
`chat_completion` records the final outcome and evidence availability, and
the request summary records total latency and model-call count.

Normal logs include prompt character counts, context and response budgets, a
SHA-256 fingerprint of system instructions, the SQL query name, catalog metric
and aggregation names, and the inclusive day count. The system fingerprint
includes the reference date and catalog. Model completion metadata includes
usage and finish reason when the provider supplies them. Questions, responses,
exact user dates, profile IDs, readings and credentials are excluded from the
normal application log.

## Explicit local sensitive diagnostics

Add to your existing, ignored `garmin-chat/values.local.yaml` and deploy manually:

```yaml
chat:
  diagnosticsEnabled: true
```

Keep any other existing `chat` overrides in that same mapping. `make chat-up`
rolls out the backend with the `chat-diagnostics` Spring profile. It writes
`/tmp/chat-diagnostics/chat.log` **inside the backend container**, not stdout.
The Docker entrypoint uses `umask 077` (directory 0700 and file 0600 on creation).
The non-root backend owns the files. No new Secret, PVC or external service is
created. Files are ephemeral; collect needed traces before Pod replacement.

The dedicated logger has no console propagation. The active file rolls at 5 MB
or a date boundary, archives retain two days and are capped at 20 MB; allow for
the active file and the event that crosses a threshold. Retention is evaluated
as logging/rollover occurs, not by an independent deletion timer.

Each trace event is one JSON-escaped line with the same request ID:

- `model_request`: turn number, messages, options and tool definition.
- `model_response`: turn number and assistant message, including any tool calls.
- `sql_request`: validated query with catalog metrics and resolved inclusive dates.
- `sql_evidence`: tool values and coverage.

These files contain **personal health information and user-supplied text**.
They do not intentionally include configuration Secrets, database credentials,
raw rows or profile IDs, but anything typed into a question can appear verbatim.
Keep traces local, out of Git and PR attachments; share only reviewed, redacted
examples. Do not use this profile in a shared/production deployment.

Find the current backend Pod and view a trace privately:

```sh
kubectl --context kind-garmin-ai-analytics -n garmin-analytics get pods \
  -l app.kubernetes.io/instance=garmin-chat,app.kubernetes.io/component=backend
# Substitute the Pod name; do not paste sensitive output into an issue.
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  exec POD_NAME -- cat /tmp/chat-diagnostics/chat.log
```

When finished, set `diagnosticsEnabled: false` and redeploy. Pod replacement
removes its ephemeral traces. Delete any private copies you made as well.
For a directly launched backend, use `SPRING_PROFILES_ACTIVE=chat-diagnostics`,
`GARMIN_CHAT_DIAGNOSTIC_DIRECTORY` pointing at a private directory, and launch
Java with `umask 077`. Broad application DEBUG is not needed.

## Repeatable natural-language cases

Run every case three times, sequentially, against the same database snapshot,
timezone, reference date and resource allocation. Record git revision, prompt
fingerprints, Ollama version, model tag **and installed digest**, context/output
budgets and CPU/memory limits. Do not compare runs across midnight.
`last week` means previous Monday–Sunday; `past week`/`last seven days` mean
seven completed days ending yesterday. No expected numerical answers are
invented below: establish them independently from PostgreSQL.

| # | Question | Expected interpretation |
| --- | --- | --- |
| 1 | How many steps did I take in total last week? | totalSteps / SUM / LAST_WEEK |
| 2 | What was my average daily step count over the past week? | totalSteps / AVG / LAST_N_DAYS=7 |
| 3 | Lowest daily step count in the last seven days? | totalSteps / MIN / LAST_N_DAYS=7 |
| 4 | Highest daily step count last week? | totalSteps / MAX / LAST_WEEK |
| 5 | Average resting heart rate last week? | restingHeartRate / AVG / LAST_WEEK |
| 6 | Lowest resting heart rate over the past week? | restingHeartRate / MIN / LAST_N_DAYS=7 |
| 7 | Highest resting heart rate in the last seven days? | restingHeartRate / MAX / LAST_N_DAYS=7 |
| 8 | Total active calories last week? | activeKilocalories / SUM / LAST_WEEK |
| 9 | Average daily active calories over the past week? | activeKilocalories / AVG / LAST_N_DAYS=7 |
| 10 | Lowest daily active calories in the last seven days? | activeKilocalories / MIN / LAST_N_DAYS=7 |
| 11 | Highest daily active calories last week? | activeKilocalories / MAX / LAST_WEEK |
| 12 | Total recorded daily sleep duration last week? | sleepingSeconds / SUM / LAST_WEEK |
| 13 | Average recorded daily sleep duration over the past week? | sleepingSeconds / AVG / LAST_N_DAYS=7 |
| 14 | Shortest recorded daily sleep duration in the last seven days? | sleepingSeconds / MIN / LAST_N_DAYS=7 |
| 15 | Longest recorded daily sleep duration last week? | sleepingSeconds / MAX / LAST_WEEK |
| 16 | How active have I been over the past week? | Clarify metric/operation; never claim the relative period is absent |
| 17 | Average resting heart rate? | Clarification without a tool call |
| 18 | Total steps from 2026-02-30 to 2026-03-01? | Invalid date rejected before SQL |
| 19 | Do days with more physical activity tend to be followed by longer sleep? | Unsupported correlation, with no fabricated finding |
| 20 | Is my resting heart rate healthy last week? | Unsupported health assessment |
| 21 | how productive i was last week ? | Clarify work versus physical activity without a tool call |

Cases 1–15 cover all currently permitted metric/aggregation combinations. Case 16
is the exact reported failure, not a disguised steps-only question. The current
prompt says ambiguous questions should clarify rather than guess. Do not label
an incorrect date error as a successful clarification.

Use the existing API, for example through `make chat-ui`:

```sh
curl --fail-with-body --max-time 690 http://127.0.0.1:5173/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"How active have I been over the past week?"}'
```

Inspect the first `model_response` for the structured route. The output schema
offers one shape per route, with only the fields that route requires. Java
still checks the decoded output. On `ANALYZE`, Java executes the selection once.
Compare `sql_request` and `sql_evidence` with the final explanation. No model tool
call is needed. `chat_routing_validation_rejected` records the safe validation
reason when generated parameters fail, without logging raw question text.

For the short routing regression check, use cases 17, 19 and 21 above, then ask
“How many steps yesterday?” and “How many steps last week?”. Confirm one routing
call and zero SQL for the productivity, activity–sleep, and missing-period cases. Confirm one SQL execution before explanation
for the step questions, with yesterday resolved in the configured
timezone and last week resolved as the previous Monday through Sunday. Use one
known empty period and one partially covered period to verify that missing
readings and recorded zeros stay distinct.

Record undecodable output or a response outside the wire contract as
`malformed_output`. Record route-specific field conflicts as
`inconsistent_decision` and rejected analytics parameters as
`invalid_analytics_arguments`. In a separate evaluation column, record
**semantic mismatch** if a contract-valid decision misreads the question,
such as asking for dates already given or substituting steps for productivity.
The backend's `accepted` event indicates validation success only. Use the
original question and the private routing trace to assess relevance.

Check malformed routing, contradictory periods, unexpected explanation tool calls, database
failure and timeout with the offline mocked cases. Model behavior on the live
cluster is **pending**. Record routing latency separately and compare total
latency and model-call count with the previous flow. Analytical requests now
make up to two model calls under the same request deadline.

Independently check accepted ranges in PostgreSQL using the configured reader
and the resolved dates. Reproduce SUM/AVG/MIN/MAX and numeric-only coverage from
`staging.stg_garmin_daily_summary`; review the existing
[`metric-aggregation.sql`](../backend/src/main/resources/sql/metric-aggregation.sql)
for column names and missingness categories. Do not merely compare two model
answers or use the service result itself as the reference. Preserve missing,
null, wrong-type and zero distinctions. Sleep is the daily-summary clipped
`sleepingSeconds` field, not primary overnight sleep or sleep quality.

For each repetition, record the expected and actual tool selection, resolved dates,
SQL-checked value and coverage, explanation correctness, stage/total latency,
usage if available, and failure classification. Record CPU/memory separately
with your normal cluster monitoring. Include cold versus warm runs; compare
warm median and worst latency. Existing unit tests cover synthetic edge cases;
they do not replace these real-inference checks.

## Candidate comparison (not yet run)

Baseline: `qwen2.5:1.5b-instruct-q4_K_M` with current settings.
Candidate: upstream [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507),
using the explicit Ollama tag
[`qwen3:4b-instruct-2507-q4_K_M`](https://ollama.com/library/qwen3:4b-instruct-2507-q4_K_M).
This is a candidate to evaluate, not a demonstrated improvement for this app.

Use the existing `ollama.model` override in your private values file; do not add
another model service or change business rules. Verify the selected tag loads
on the pinned Ollama image before running the suite. If an Ollama upgrade is
necessary, evaluate it separately and record the exact version. A 4B model may
exceed the existing 3 GiB container limit once runtime/context memory is added;
measure available node headroom and choose explicit resources before testing.
Do not assume model-file size equals runtime memory or raise context to the
model's maximum. Keep context, temperature, output limits, data and prompts the
same for the model comparison wherever compatible.

Only select a new default after better intent accuracy, honest explanations,
acceptable latency and no OOM/restart regression are demonstrated. Log the
result even if neither model is good enough. Prompt tuning should be a separate
measured change after the baseline, not silently bundled into the comparison.

Historical baseline: the owner completed 45 isolated local inference calls on
19 November 2026. See the [controlled evaluation report](../../docs/evaluations/2026-11-19-garmin-chat/README.md)
for reviewed results and limitations. No larger candidate was evaluated, and the
report is not an end-to-end deployment verification. #66 stays open.
