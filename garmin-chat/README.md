# Garmin chat in kind (#41)

The generic, single-field React chat runs with Spring Boot/Spring AI and CPU
Ollama in three single-replica Deployments in `garmin-analytics`. Production
Nginx serves React and proxies relative `/api` requests to the backend on 8081.
The backend uses the existing analytics PostgreSQL Service and internal Ollama
Service on 11434. No cluster recreation or existing Airflow/database changes
are required.

The first Spring AI call sends Ollama the question, the metric catalog and the
server's reference date and timezone. Ollama returns a schema-constrained
`ANALYZE`, `CLARIFY`, or `UNSUPPORTED` decision. Java validates the decision.
Clarification and unsupported requests finish without a tool call or database
access.

For `ANALYZE`, Java executes the validated metric, calculation and period once
through the analytics service. The fixed SQL returns compact values and coverage
for the server profile. Only then does Spring AI ask the model to explain the
verified evidence. The model cannot skip the query or change its parameters in
a second tool call. A successful query with no numeric data yields an explicit
no-data response without another inference call.

Routing is still model-generated: schema-valid JSON does not prove the selected
calculation answers the question. Verify routing quality with the configured
model before considering a demo ready. The prompt includes examples of ambiguous
goals and unsupported relationships; there are no question-specific Java rules.

Questions are independent. Resend the full question after a clarification.
Raw daily summaries and profile IDs stay on the server.

See [request diagnostics and the repeatable model evaluation](docs/chat-diagnostics.md)
for the opt-in local trace mode and the #66 verification checklist.

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
| `chat.modelTimeoutSeconds` | 300 seconds per Ollama HTTP call |
| `chat.executionTimeoutSeconds` | Total backend deadline. Defaults to two model timeouts plus 60 seconds (660 seconds with default settings). |
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

Ollama publishes a [tool-enabled template for this exact tag](https://ollama.com/library/qwen2.5:1.5b-instruct-q4_K_M/blobs/eb4402837c78).
The installed model must advertise `tools` through `/api/show`. The library
template and capability check establish support for the protocol. Accuracy of
tool selection, arguments and the final answer still needs live verification.

CPU inference can take time even with compact evidence. A supported question can
require two inference turns: routing and evidence explanation. These calls, the
capability check and database work share the existing backend deadline. Helm sets the frontend
proxy timeout 30 seconds above that deadline.

Direct backend runs configure the HTTP read timeout with
`OLLAMA_REQUEST_TIMEOUT` (default `300s`) and the independent application
deadline with `GARMIN_CHAT_EXECUTION_TIMEOUT` (default `660s`). If you override
the transport timeout, allow time for up to two turns, capability checks and SQL in
the application deadline. Set any external proxy timeout above the application
deadline. Timeouts return HTTP 503 with a retry hint.

Optional deployment overrides:

```sh
cp garmin-chat/values.example.yaml garmin-chat/values.local.yaml
```

The local file is ignored by Git and optional. It can change model, context,
resources, timezone and response reserve. It contains no fixed dates or personal
profile ID. Existing overrides are preserved; no deployment happens during tests.

## Analytics selection contract

The router selects these parameters; Java executes them through the analytics service:

```json
{
  "metrics": ["restingHeartRate"],
  "aggregation": "AVG",
  "period": {"type": "LAST_WEEK"}
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
duplicate metrics, incorrect JSON types, contradictory periods
and invalid/future dates are rejected. The reference date and timezone stay in
the system message; the question is passed verbatim as the user message.

The Java metric catalog generates the system context before routing.
It lists field meaning, units, synonyms, allowed aggregations and limitations
for the four supported fields. The model should clarify ambiguity without
querying the database, including questions about work productivity. The service covers
scalar aggregations over a single period. Sleep quality, medical conclusions,
correlation, period comparisons, trends, rankings and thresholds are outside
that scope.

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
incomplete in the final answer. No missing reading is replaced with zero.

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
same terminal. Open the address on the `Forwarding from` line. It normally uses
**http://127.0.0.1:5173** and uses a free port if the default is occupied. Set
`CHAT_PORT` to require a specific port. Keep the terminal open. Ctrl+C stops
forwarding while Pods keep running.

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
Ollama shows the selected model and declares its tool capability. An unavailable
or incompatible model yields HTTP 503 with `Retry-After: 10` and a safe reason.
Database failures have a separate safe error. Kubernetes probes keep the backend able to explain model
unavailability; Ollama server readiness permits setup before a model exists.
An `Insufficient cpu` event means a scheduling reservation problem, not an
active download. Interrupt an unsuccessful wait, fix resources and rerun
`chat-up`; a cluster rebuild is not required.

## Analytics execution, evidence and context budget

Java executes accepted selections directly through `GarminAnalyticsService`.
The service checks field and operation allowlists, compatibility, periods and
query range before PostgreSQL, then validates the returned metrics, operation,
units, dates and coverage. Repository errors remain database failures, never
model input. The existing tool adapter is retained for future tool-driven flows;
the routed scalar workflow does not register callbacks or a tool-loop advisor.
Unexpected tool calls during explanation are rejected without dispatching them.

Named periods require only their type. `LAST_N_DAYS` additionally needs `days`;
`EXPLICIT` needs `startDate` and `endDate`. Omitted auxiliary fields use neutral
Java defaults. Conflicting nonempty dates or nonzero counts for named periods
remain invalid. Existing full objects with neutral fields still decode.

The synchronous request uses one virtual worker so the HTTP caller can
return when its deadline expires. The worker checks the deadline before
checking Ollama availability, before each model turn and before and after analytics execution.
Expiration or interruption of the waiting caller interrupts the worker.
Spring's JDK HTTP transport attempts cancellation while waiting for an exchange.
In-flight HTTP and JDBC operations remain subject to their own timeouts.
A client disconnect on the synchronous servlet endpoint may only be noticed
when the server writes the response.

The analytics service returns `QueryEvidence`, for example (synthetic):

```json
{
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

The explanation model gets the original question and the complete verified
`QueryEvidence` in a separate system message. It is instructed to use supplied values, identify partial totals
and explain coverage, including missing readings and valid zeros.

Before each inference turn, `PromptBudget` counts UTF-8 bytes for the messages,
routing schema, tool definition, arguments and result, using one estimated token per byte.
It also reserves 512 tokens for template overhead and `chat.responseTokens`
for output. Oversized questions stop before Ollama and SQL. If the conversation
including evidence exceeds the budget, the backend requests a shorter question
or fewer metrics and the final model turn is skipped. The evidence is passed
intact when it fits.

`POST /api/chat` accepts `{"message":"..."}` and returns `answer`, `startDate`
and `endDate`. Dates come from successfully executed evidence and describe
the inclusive range, including gaps. Clarification and unsupported answers
without a query have null dates. Blank requests return HTTP 400, and
database and model failures return safe HTTP 503s.

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
   terminal open and open the address on the `Forwarding from` line. Confirm
   the introduction reads “Ask your Garmin watch”. The deployment reuses the
   local values and reader Secret in the existing cluster. Use `make chat-ui`
   to restart forwarding on its own.

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
   should finish without a tool call or SQL.

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

The backend uses packages under `com.garmin.analytics.chat`.

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP validation and error responses |
| `service` | Chat use-case entry point |
| `execution` | Request correlation, deadline and cancellation |
| `ai` | ChatClient conversation, model transport, availability and per-turn checks |
| `ai.routing` | Structured decision decoding and validation |
| `ai.prompt` | Prompt assembly from resources and context budgeting |
| `ai.tool` | Routed analytics execution and retained tool adapters |
| `analytics` | Business validation, date resolution and evidence assembly |
| `repository` | Fixed SQL, bound parameters and result mapping |
| `model` | Shared records and metric catalog |
| `diagnostics` | Opt-in structured diagnostics |
| `config` | Application and transport wiring |

Application contracts live in their owning package. Their implementations
live in its `impl` subpackage and use the contract name, an optional technology
qualifier, and the `Impl` suffix. For example, `DailySummaryRepository` is
implemented by `repository.impl.DailySummaryRepositoryPostgresImpl`.

## Offline verification

The [AI evaluation suite](evaluation/README.md) provides versioned synthetic
cases, a test plan, production-prompt export, opt-in Ollama/application checks,
and baseline comparison. Start with `make chat-eval-test`; live model quality
is evaluated separately from the offline tests below.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)  # macOS; select Java 25 on other OSes
mvn -B -ntp -f garmin-chat/backend/pom.xml \
  -Dtest='*Test,!PostgresMetricAggregationDatabaseTest' clean test
npm --prefix garmin-chat/frontend ci
npm --prefix garmin-chat/frontend test
npm --prefix garmin-chat/frontend run build
python3 -m venv /tmp/garmin-chat-checks
/tmp/garmin-chat-checks/bin/python -m pip install -r garmin-chat/tests/requirements.txt
PATH="/tmp/garmin-chat-checks/bin:$PATH" make chat-validate
```

Tests use synthetic summaries, a fixed Clock, stub repositories/models and fake
HTTP transport/CLIs. They cover explicit/relative/invalid dates, timezone and DST,
clarification and unsupported answers without tools, bound profile/date arguments,
compact evidence with distinct coverage and no raw payloads, no data,
model/database failures, call limits, the request deadline, and oversized
questions and evidence. Budget tests include system text, JSON, question, response
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

Before the tool-calling refactor, Step 3 verification passed 226 offline tests
and 35 read-only PostgreSQL tests and built the executable JAR. All model
responses in these checks are stubbed. The opt-in suite requires
database environment settings. Both suites run without an Ollama connection.
