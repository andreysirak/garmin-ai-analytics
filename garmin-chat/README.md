# Garmin chat in kind (#41)

The generic, single-field React chat runs with Spring Boot/Spring AI and CPU
Ollama in three single-replica Deployments in `garmin-analytics`. Production
Nginx serves React and proxies relative `/api` requests to the backend on 8081.
The backend uses the existing analytics PostgreSQL Service and internal Ollama
Service on 11434. No cluster recreation or existing Airflow/database changes
are required.

A normal question follows this flow:

1. Ollama interprets the question as metrics, an aggregation and a period.
2. Java validates the intent and resolves dates using an injected Clock/timezone.
3. Fixed parameterized SQL calculates values and coverage for the server profile.
4. A conservative check verifies the complete explanation request fits its context.
5. The same Ollama model explains compact evidence alongside the original question.

Raw daily summaries and profile identifiers are never sent to either model call.
Clarification, unsupported questions and periods without numeric data return
application-authored messages without an explanation call.

Questions are independent. Memory, charts, model-generated SQL, tools, MCP and
RAG remain deferred. Dates come from the question; a week is a demo example,
not a fixed query window. Missing dates trigger clarification before any query.

## Configuration and prerequisites

Use the existing single-node `kind-garmin-ai-analytics` context, analytics
PostgreSQL and `staging.stg_garmin_daily_summary` with backfilled data. kind's
`standard` storage class must be available. Install Docker, kind, Helm, kubectl
and Python. Docker builds provide Java 25 and Node; local Java/Node are needed
only for the offline checks below.

The profile is **server-side configuration**, not question/model/client input.
In Kubernetes, `GARMIN_CHAT_PROFILE_ID` comes from key `profile-id` in
`garmin-chat-database`. It must be a positive profile ID present in staging.
Local backend development supplies that environment variable directly.

[Chart defaults](../infrastructure/helm/garmin-chat/values.yaml) configure:

| Setting | Default / meaning |
| --- | --- |
| `chat.timezone` | `Europe/Oslo`; IANA timezone for relative dates |
| `chat.responseTokens` | 1024; answer token limit and reserved context space |
| `chat.maxQueryDays` | 31 inclusive calendar days; rejects larger ranges before PostgreSQL |
| `chat.modelTimeoutSeconds` | 300 per Ollama call; proxy allows both calls plus 30 seconds |
| `ollama.contextLength` | 8192; used by both Spring AI and Ollama |
| `ollama.model` | `qwen2.5:1.5b-instruct-q4_K_M` |
| `ollama.resources` | CPU request 250m / limit 2; memory request 1536Mi / limit 3Gi |
| `database.existingSecret` | `garmin-chat-database` |
| `database.profileIdKey` | `profile-id` |

The model is a small [quantized Qwen model](https://ollama.com/library/qwen2.5:1.5b-instruct-q4_K_M)
on pinned [Ollama 0.40.16](https://github.com/ollama/ollama/releases/tag/v0.40.16).
Inference uses CPU, one loaded model, one concurrent inference and one queued
request. Requests/limits/model are configurable. Keep the configured context
within the selected model's supported window and available memory.

CPU inference can take time even with compact evidence. The non-streaming
request includes intent interpretation followed by an explanation call; the
frontend waits for both. Set `chat.modelTimeoutSeconds` higher on a slower
machine. Helm adjusts the proxy timeout to twice that value plus 30 seconds, so the backend can return
a useful timeout error before Nginx closes the request. Direct backend runs use
`OLLAMA_REQUEST_TIMEOUT` (a duration such as `300s`). A timeout returns HTTP 503
with a retry hint and asks for a shorter period; evidence is never truncated.
The browser stays on “Preparing your answer…” while waiting.

Optional deployment overrides:

```sh
cp garmin-chat/values.example.yaml garmin-chat/values.local.yaml
```

The local file is ignored by Git and optional. It can change model, context,
resources, timezone and response reserve. It contains no fixed dates or personal
profile ID. Existing overrides are preserved; no deployment happens during tests.

## Structured analytics review checkpoints

`QuestionDateResolver.intentPrompt` and `resolveIntent` define the interpretation
contract below. `GarminChatService` connects validated intent to repository
aggregation and compact evidence. The Step 1 unsupported-question UX changes
are local and require approved deployment before live verification.

```json
{
  "status": "OK",
  "metrics": ["restingHeartRate"],
  "aggregation": "AVG",
  "period": {"type": "LAST_WEEK", "days": 0, "startDate": "", "endDate": ""}
}
```

| Canonical field | Evidence unit | Allowed aggregations |
| --- | --- | --- |
| `totalSteps` | steps | SUM, AVG, MIN, MAX |
| `restingHeartRate` | bpm | AVG, MIN, MAX |
| `activeKilocalories` | kcal | SUM, AVG, MIN, MAX |
| `sleepingSeconds` | hours (SQL conversion from seconds) | SUM, AVG, MIN, MAX |

One operation applies independently to one to four distinct metrics. Profile,
field metadata, units and conversion divisors come from server configuration
and the Java catalog, never the model. Unknown properties, fields and operations,
duplicate metrics/JSON keys, coercible-but-wrong JSON types, contradictory periods
and invalid/future dates are rejected. The reference date and timezone stay in
the system message; the question is passed verbatim as the user message.

The other statuses are `CLARIFICATION` and `UNSUPPORTED`; each requires an empty
metrics array, aggregation `NONE` and the canonical `CLARIFICATION` period with
days 0 and empty date strings. Neither produces executable parameters. Missing
metric/operation/period needs clarification. The interpretation prompt rejects
sleep quality/stages/scores, medical conclusions, advice, causal findings,
comparisons, trends, rankings, thresholds and correlation. It must reject an
unsupported part of a question rather than silently answering a supported part.
The explanation prompt repeats these restrictions. Live evaluation of the new
interpretation and explanation prompts remains a review checkpoint.

`garmin.chat.max-query-days` / `GARMIN_CHAT_MAX_QUERY_DAYS` is a positive server
setting, default 31; Helm exposes `chat.maxQueryDays`. Count both boundary dates.
No period is silently shortened. Existing relative-date semantics are retained:
last N days excludes today, last week is the previous Monday–Sunday, and explicit
dates require a four-digit year. Shared period rules live in one prompt resource.

Coverage contract implemented by aggregation SQL and the `DataCoverage` record:

| Disjoint day category, per metric | Definition |
| --- | --- |
| `missingSummaryDays` | No row for the configured profile and expected date |
| `missingMetricDays` | Summary exists but the selected key is absent |
| `nullMetricDays` | Summary and key exist; the value is JSON null |
| `invalidTypeDays` | Key contains a non-null value whose JSON type is not number; numeric strings are invalid |
| `daysWithData` | Key contains a JSON number, including recorded zero |

Each expected calendar day belongs to exactly one category. `expectedDays` is
the inclusive date count; `summaryDays = expectedDays - missingSummaryDays`.
`missingDays = missingSummaryDays + missingMetricDays + nullMetricDays`, excluding
invalid types. Consequently `expectedDays = daysWithData + missingDays + invalidTypeDays`.
`zeroDays` is a subset of `daysWithData`, not another category. Aggregates use
numeric readings only; all-unavailable values stay null, including SUM. AVG
divides by numeric readings, not expected days. `complete()` is true only when
every expected day has a numeric reading. Partial totals must be labelled
incomplete by the explanation model. No missing reading is replaced with zero.

### PostgreSQL aggregation

`DailySummaryRepository.aggregate(QueryIntent)` returns one `MetricAggregation`
per requested metric: typed metric/operation, PostgreSQL `numeric` as Java
`BigDecimal` (nullable), unit, date range and coverage. It takes the profile from
`GarminChatProperties`, not the intent or a caller-supplied ID. The repository
also rejects reversed or oversized ranges before JDBC.

One [fixed SQL template](backend/src/main/resources/sql/metric-aggregation.sql)
runs per metric in a short read-only, repeatable-read transaction. Profile,
inclusive dates, catalog field key, allowed operation and conversion divisor
are bound parameters; SQL text never comes from the model. Expected calendar
dates are left-joined to staging so completely absent summaries remain visible.
Only JSON numbers are cast, and PostgreSQL performs SUM/AVG/MIN/MAX before
dividing sleep seconds by 3600. Decimal precision is retained without Java
rounding. Numeric strings, booleans, arrays and objects are invalid types.
Numeric plausibility or medical interpretation is outside this query's scope.

The legacy full-summary repository method remains available, but the chat
service calls only aggregation. The React interface is unchanged.

## Reader login and Secret (manual setup/review)

Use the dedicated read-only login, not an administrator or loader. If absent,
create it once after staging exists:

```sh
psql -X -h 127.0.0.1 -p 5432 -U garmin_analytics -d garmin_analytics -W \
  -f garmin-chat/sql/create-reader.sql
```

The script grants `garmin_chat_reader` only CONNECT, schema USAGE and SELECT on
the daily-summary table. Read-only transactions are its default; Hikari also
marks connections read-only. The script prompts for a password using `\password`
and fails if the role already exists. Reuse an existing working login instead
of rerunning role creation. Homebrew libpq provides `psql` on macOS.

In the existing mode-0600 `config/credentials.local.json`, preserve all entries
and add `postgresql.garmin_chat_reader_password` and a **positive JSON integer**
at `chat.profile_id`. These additional chat entries are not managed by the
existing infrastructure bootstrap helpers.

For a new installation, create the Secret through stdin, without printing or
committing its contents. Run from the repository root:

```sh
python3 - <<'PY' | kubectl --context kind-garmin-ai-analytics -n garmin-analytics create -f -
import json
import sys
sys.path.insert(0, "infrastructure/scripts")
from local_credentials import DEFAULT_FILE, load_private_json, value
credentials = load_private_json(DEFAULT_FILE)
password = value(credentials, "postgresql.garmin_chat_reader_password")
profile = credentials.get("chat", {}).get("profile_id")
if type(profile) is not int or profile <= 0:
    raise SystemExit("Set chat.profile_id to the selected positive profile ID")
print(json.dumps({
    "apiVersion": "v1", "kind": "Secret",
    "metadata": {"name": "garmin-chat-database", "namespace": "garmin-analytics"},
    "type": "Opaque",
    "stringData": {"username": "garmin_chat_reader", "password": password,
                   "profile-id": str(profile)}
}))
PY
```

`create` fails rather than replacing an existing Secret. For an **existing chat
installation**, add just the new profile key before deploying this change;
leave its working username and password untouched:

```sh
python3 - <<'PY' | kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  patch secret garmin-chat-database --type merge --patch-file=/dev/stdin
import json
import sys
sys.path.insert(0, "infrastructure/scripts")
from local_credentials import DEFAULT_FILE, load_private_json
profile = load_private_json(DEFAULT_FILE).get("chat", {}).get("profile_id")
if type(profile) is not int or profile <= 0:
    raise SystemExit("Set chat.profile_id to the selected positive profile ID")
print(json.dumps({"stringData": {"profile-id": str(profile)}}))
PY
```

Helm injects `username`, `password` and `profile-id` using `secretKeyRef`, without
storing their values in the release. Custom Secret/key names are configurable
through `database.existingSecret`, `usernameKey`, `passwordKey`, `profileIdKey`.
The internal database URL defaults to
`garmin-postgresql.garmin-analytics.svc.cluster.local:5432/garmin_analytics`.
This task does not change the live Secret or private credentials.

## Deploy and open (for human review)

```sh
make chat-up
# Optional: make chat-up CHAT_VALUES=/private/path/chat-values.yaml
```

This lints values, builds/loads React and Spring Boot images into the existing
kind cluster, upgrades/installs the Helm chart, and waits for workloads and the
model-pull Job (timeout 30 minutes). A build ID restarts app Pods to pick up newly
loaded local images. After success, it starts frontend port-forwarding in the
same terminal. Once “Forwarding from…” appears, open **http://127.0.0.1:5173**.
Keep that terminal open; Ctrl+C stops forwarding while Pods keep running.

```sh
make chat-ui  # Reconnect later without rebuilding or redeploying.
# Optional: make chat-ui CHAT_PORT=5174
```

The ordinary setup Job runs per Helm revision and pulls a model only if missing.
It reaches Ollama through its Service, so downloads are written by the server
into the model PVC. Failed pulls fail the Job and `chat-up`; correct the cause
and rerun to create a fresh Job. Pod restarts reuse the model files. The PVC is
retained on Helm uninstall, but kind's node-local storage does not survive
cluster deletion. Existing host-backed PostgreSQL storage remains unchanged.

Inspect readiness and initialization:

```sh
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  get deployments,pods,jobs,pvc -l app.kubernetes.io/instance=garmin-chat
kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
  logs -l app.kubernetes.io/instance=garmin-chat,app.kubernetes.io/component=model-pull \
  --all-containers=true --prefix --tail=50
```

The backend exposes `/actuator/health/ollamaModel` via its internal Service
(port-forward backend 8081 for direct inspection). It reports available when
Ollama can show the selected model; this does not prove answer correctness.
Until then, chat returns HTTP 503 with `Retry-After: 10`. Database failures have
a separate safe error. Kubernetes probes keep the backend able to explain model
unavailability; Ollama server readiness permits setup before a model exists.
An `Insufficient cpu` event means a scheduling reservation problem, not an
active download. Interrupt an unsuccessful wait, fix resources and rerun
`chat-up`; a cluster rebuild is not required.

## Intent, evidence and context budget

Spring AI's native JSON-schema format constrains interpretation; strict Jackson
parsing and Java validation check the response before SQL. The reference date
and configured timezone are system metadata, separate from the verbatim question.
Explicit dates are inclusive and require a year. Last N days excludes today;
last week is the previous Monday–Sunday. Java handles DST and month boundaries.
A missing or ambiguous metric, operation or period needs clarification.

The service checks that repository results match the requested metrics,
operation, dates, units and expected coverage. An inconsistent repository result
returns the safe database error rather than reaching the explanation model.
The explanation message contains only `QueryEvidence`, for example (synthetic):

```json
{
  "question": "Average recorded sleep duration from 2024-02-26 to 2024-03-03?",
  "aggregation": "AVG",
  "dateRange": {"startDate": "2024-02-26", "endDate": "2024-03-03"},
  "metrics": [{
    "metric": "sleepingSeconds", "value": 1.5, "unit": "hours",
    "coverage": {
      "expectedDays": 7, "summaryDays": 6, "daysWithData": 3, "zeroDays": 1,
      "missingSummaryDays": 1, "missingMetricDays": 1, "nullMetricDays": 1,
      "invalidTypeDays": 1, "missingDays": 3, "complete": false
    }
  }]
}
```

The explanation prompt requires supplied values only, no further calculations,
unit conversions or invented measurements. It requires incomplete coverage and
partial totals to be labelled, and unknown requested metrics to be reported.
Sleep duration cannot establish sleep quality; medical conclusions, advice,
comparisons, trends and correlations are outside the supported contract.

Before each model call, `PromptBudget` reserves:

```text
UTF-8 bytes of every prompt message + 512 template tokens + chat.responseTokens
```

One estimated token per byte deliberately overestimates text, including non-Latin
text. This includes the full system prompt, original question and all compact
evidence. Interpretation output is limited to at most 384 tokens; explanation
uses `chat.responseTokens`. Oversized questions skip both model calls and SQL.
Oversized evidence skips explanation and requests a shorter period or fewer
metrics. Nothing is silently removed. Longer periods no longer send more raw
JSON; the maximum date range and context checks are separate safeguards.

`POST /api/chat` still accepts `{"message":"..."}` and returns `answer`,
`startDate`, `endDate`. Dates cover the validated requested range, including gaps.
No summaries or no numeric readings produce direct messages with those dates.
When some metrics have data and others are unknown, all requested metrics and
coverage reach explanation. Clarification and unsupported intent have null
dates; resend the complete question because there is no conversation memory.
Blank requests remain HTTP 400; database/model failures remain safe HTTP 503s.

## Step 1 manual verification (deployment requires approval)

The local automated checks do not establish live Ollama answer correctness.
Run this checklist after the application deployment has been explicitly approved.
It uses the existing summary analytics; correlation remains unsupported.

1. **After deployment approval only**, rebuild/load the app images and upgrade
   the existing chat release:

   ```sh
   make chat-up
   ```

   This also checks the cached model and forwards the frontend. Keep the
   terminal open and open http://127.0.0.1:5173. Confirm the introduction reads
   “Ask your Garmin watch”. Existing local values and the reader Secret are
   reused; no cluster recreation is needed. If only forwarding needs restarting,
   use `make chat-ui`.

2. Use a known backfilled week. Replace the example dates below if necessary.
   Each answer should show the requested inclusive range, correct units and
   coverage, with numbers matching PostgreSQL:

   | Question | Expected calculation/unit |
   | --- | --- |
   | How many steps did I record in total from 2026-10-05 through 2026-10-11? | SUM / steps |
   | What was my average resting heart rate from 2026-10-05 through 2026-10-11? | AVG / bpm |
   | What was my maximum daily active calorie burn from 2026-10-05 through 2026-10-11? | MAX / kcal |
   | What was my average daily recorded sleep time from 2026-10-05 through 2026-10-11? | AVG / hours |
   | What was my minimum daily step count from 2026-10-05 through 2026-10-11? | MIN / steps |

   Sleep summaries still use the existing calendar-day `sleepingSeconds` field
   divided by 3600. They must not be described as a verified overnight sleep
   duration, a sleep-quality assessment or a medical finding.

   Set `GARMIN_CHAT_PROFILE_ID` privately in the checking terminal to the
   server-configured profile. Compare with the fixed SQL using the existing
   reader login/password prompt; for example:

   ```sh
   psql -X -h 127.0.0.1 -p 5432 -U garmin_chat_reader -d garmin_analytics -W \
     --set=profileId="$GARMIN_CHAT_PROFILE_ID" \
     --set=startDate="'2026-10-05'" --set=endDate="'2026-10-11'" \
     --set=metric="'restingHeartRate'" --set=aggregation="'AVG'" --set=divisor=1 \
     --file=garmin-chat/backend/src/main/resources/sql/metric-aggregation.sql
   ```

   Repeat with the selected field/operation; use divisor 3600 for
   `sleepingSeconds` and 1 for other metrics. Recorded zeros must stay valid,
   unknowns must remain unknown and incomplete totals must be labelled partial.

3. Ask “Do days with more physical activity tend to be followed by longer
   sleep?” and “Correlate steps and sleep from 2026-10-05 through 2026-10-11”.
   Expect friendly unsupported guidance and one supported example, no coefficient
   or invented conclusion, and no date caption. The old “Unsupported aggregation”
   and “Date range not selected” messages must not appear. Unsupported requests
   should stop before SQL and the explanation call, as the automated tests check.

4. Ask “Average resting heart rate” to check clarification, then ask a dated
   question containing an impossible date such as 2026-02-30. Expect clear
   guidance without a date caption. Ask for total steps over a known empty
   period, such as 2000-01-01 through 2000-01-07: expect a direct no-data answer
   with those dates still displayed.

5. Repeat one supported dated question three times. Wording may vary; the
   calculated values, units, dates and coverage must remain consistent with SQL.
   Then alternate supported and unsupported questions: the date caption should
   disappear for rejection and reappear for a supported result. Inspect the
   browser Network panel if needed: requests remain `{"message":"..."}` and
   successful responses contain only `answer`, `startDate`, `endDate`.

Keep profile IDs, credentials, real measurements and answers out of commits and
public logs. No deployment or live Ollama test has been performed for this UX
change; record actual manual results only after executing the checks above.

## Backend packages

Under `com.garmin.analytics.chat`, `controller` owns HTTP requests and errors,
`service` coordinates the flow, `repository` owns PostgreSQL access, `model`
contains shared records, `ai` handles interpretation, prompts and model
availability, and `config` holds server settings. `GarminChatApplication` stays
at the root for component scanning. Tests mirror these packages and share
test-only fixtures through `support`.

## Offline verification

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)  # macOS; select Java 25 on other OSes
mvn -B -ntp -f garmin-chat/backend/pom.xml clean verify
npm --prefix garmin-chat/frontend ci
npm --prefix garmin-chat/frontend test
npm --prefix garmin-chat/frontend run build
python3 -m venv /tmp/garmin-chat-checks
/tmp/garmin-chat-checks/bin/python -m pip install -r garmin-chat/tests/requirements.txt
PATH="/tmp/garmin-chat-checks/bin:$PATH" make chat-validate
```

Tests use synthetic summaries, a fixed Clock, stub repositories/models and fake
HTTP transport/CLIs. They cover explicit/relative/invalid dates, timezone and DST,
clarification and unsupported intent before SQL, bound profile/date arguments,
compact evidence with distinct coverage and no raw payloads, no data,
model/database failures, independent questions, and oversized requests
skipping answer calls. Budget tests include system text, JSON, question, response
reserve and non-Latin text. Configuration, React, Helm render, model setup and
Make sequencing checks remain offline. No Git write operations are performed.

The PostgreSQL aggregation suite is opt-in. Set `GARMIN_CHAT_TEST_DB_URL`
(normally `jdbc:postgresql://127.0.0.1:5432/garmin_analytics`),
`GARMIN_CHAT_TEST_DB_USERNAME` and `GARMIN_CHAT_TEST_DB_PASSWORD` privately from
the existing reader Secret. Set `GARMIN_CHAT_TEST_PROFILE_ID` from its
`profile-id` key to include comparison against backfilled data, then run:

```sh
mvn -B -ntp -f garmin-chat/backend/pom.xml \
  -Dtest=PostgresMetricAggregationDatabaseTest test
```

These tests perform SELECTs only. Synthetic rows replace just the staging
relation in the production SQL, so no test table, database, container or data
writes are needed. They cover all approved operations, independent per-metric
coverage, other-profile/out-of-range exclusion, absent/null/invalid values,
recorded zeros, leap-day boundaries, decimal precision and fractional sleep
hours. The reader-role check verifies default read-only mode and no staging
write privileges. The optional real-data check selects the latest stored week
and compares all 15 supported metric/operation pairs plus coverage against
[direct table calculations](backend/src/test/resources/sql/metric-aggregation-comparison.sql).
It prints no personal readings or profile identifiers. Database-backed service
tests also verify calculated compact evidence and the no-data path with stubbed
model responses. The offline HTTP integration test exercises MVC, repository
mapping and the real Spring AI client with mocked JDBC and HTTP boundaries.

Step 3 verification: 226 offline tests and 35 read-only PostgreSQL tests passed;
the executable JAR also builds successfully. All model responses in these checks
are stubbed. No deployment, workload changes or SQL writes were performed.
Without database environment settings, the opt-in suite is skipped; no Ollama
connection is needed for either suite.
