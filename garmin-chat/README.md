# Garmin chat in kind (#41)

The generic, single-field React chat runs with Spring Boot/Spring AI and CPU
Ollama in three single-replica Deployments in `garmin-analytics`. Production
Nginx serves React and proxies relative `/api` requests to the backend on 8081.
The backend uses the existing analytics PostgreSQL Service and internal Ollama
Service on 11434. No cluster recreation or existing Airflow/database changes
are required.

A normal question follows this flow:

1. Ollama extracts a structured date selection, without seeing Garmin data.
2. Java resolves and validates the dates using an injected Clock and timezone.
3. The fixed SQL loads full daily summaries for the server-selected profile/range.
4. A conservative check verifies the complete answer request fits its context.
5. The same Ollama model answers the original question using those JSON summaries.

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

A full week of JSON can take several minutes on CPU. The non-streaming request
includes date extraction followed by an answer call; the frontend waits for
both. Set `chat.modelTimeoutSeconds` higher on a slower machine. Helm adjusts
the proxy timeout to twice that value plus 30 seconds, so the backend can return
a useful timeout error before Nginx closes the request. Direct backend runs use
`OLLAMA_REQUEST_TIMEOUT` (a duration such as `300s`). A timeout returns HTTP 503
with a retry hint and asks for a shorter period; it does not reduce the JSON.
The browser stays on “Preparing your answer…” while waiting.

Optional deployment overrides:

```sh
cp garmin-chat/values.example.yaml garmin-chat/values.local.yaml
```

The local file is ignored by Git and optional. It can change model, context,
resources, timezone and response reserve. It contains no fixed dates or personal
profile ID. Existing overrides are preserved; no deployment happens during tests.

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

## Dates, complete JSON and context budget

The date-selection resource prompt and Spring AI's native JSON-schema format
constrain Ollama to a small typed selection. Spring AI `BeanOutputConverter`
generates the schema; strict Jackson parsing and Java validation check the
response before SQL. The model never generates SQL or chooses the profile.

- Explicit ISO dates are inclusive; one date uses the same start/end. Missing
  years, ambiguous numeric dates, vague/conflicting/unsupported periods and
  malformed selections ask for clarification. Reversed, impossible and future
  dates are rejected before querying.
- “Last seven days” means seven completed local dates ending yesterday. Last N
  days and last N weeks use date arithmetic in Java, always excluding today.
- “Last week” is the previous Monday–Sunday calendar week. “This week” ends today.
  Last/this month, yesterday and today are also supported.
- An injected Clock supplies a single reference date per request in
  `chat.timezone`. DST and month/year boundaries use local calendar dates.

The reference date is system metadata, separate from the verbatim user question.
The structured schema puts `type` first, before its dependent fields. The prompt
includes multilingual examples: “последнюю неделю” means seven completed days;
“на прошлой неделе” means the previous Monday–Sunday calendar week.

The existing repository owns this one parameterized query:

```sql
SELECT user_profile_id, calendar_date, payload
FROM staging.stg_garmin_daily_summary
WHERE user_profile_id = :profileId
  AND calendar_date BETWEEN CAST(:startDate AS date) AND CAST(:endDate AS date)
ORDER BY user_profile_id, calendar_date;
```

The server profile and validated dates are bound parameters. Complete JSONB
payloads remain intact in typed records: no metric projection, field removal,
aggregation, sampling or truncation. Missing dates/fields/null readings are
unknown; recorded zeros are valid. The answer model may use any supplied fields.
Missing sleep information must be reported, not invented or fetched elsewhere.
Walking/sleep associations must not be described as causal effects.

Before the answer call, `PromptBudget` reserves:

```text
UTF-8 bytes of every prompt message + 512 template tokens + chat.responseTokens
```

One estimated token per UTF-8 byte intentionally overestimates ordinary text
and JSON, including non-Latin text. The messages contain the full system prompt,
selected period, metadata, all JSON and the original question. The answer's
`num_predict` uses the same response reserve. The date prompt is checked too;
its output limit is at most 256 tokens. Oversized questions skip both model calls.
Oversized data returns “Please choose a shorter period” with the selected dates
and skips the answer call. Nothing is silently removed to make the request fit.
The original question and JSON share one user message to keep evidence together.

`POST /api/chat` still accepts only `{"message":"..."}`. Its response still has
`answer`, `startDate`, `endDate`. Dates describe the validated requested range,
including gaps, rather than shrinking to existing rows. No summaries returns a
direct explanation and that range, without an answer call. Clarification has
null dates; resend the complete question with a clear period because there is
no chat memory. Blank HTTP messages remain HTTP 400.

## Manual checks after review (not run for this change)

1. Add the server profile key to the existing Secret, review settings, then run
   `make chat-up`. Check all three Deployments and the latest model-pull Job.
2. Choose a **known backfilled week**. For example, replace these example dates
   with a week present for your server-selected profile:

   ```sh
   curl -sS http://127.0.0.1:5173/api/chat -H 'Content-Type: application/json' \
     --data '{"message":"How did I sleep from 2026-10-05 to 2026-10-11?"}'
   ```

   Expect those inclusive dates and an answer supported by the full JSON. If
   daily summaries lack sleep details, the answer must say what is missing.
   Set `GARMIN_CHAT_PROFILE_ID` in your checking terminal to the same server
   profile, then compare privately against the exact repository query:

   ```sh
   psql -X -h 127.0.0.1 -p 5432 -U garmin_chat_reader -d garmin_analytics -W \
     --set=profileId="$GARMIN_CHAT_PROFILE_ID" \
     --set=startDate="'2026-10-05'" --set=endDate="'2026-10-11'" \
     --file=garmin-chat/backend/src/main/resources/sql/daily-summaries.sql
   ```

   A week is not guaranteed to fit every context or payload. The optional
   example values use 32768 tokens; use settings supported by your model.
3. Ask “How did I sleep last week?” and “How was my walking related to my sleep
   over the last seven days?” Compare the dates with today's date in the
   configured timezone. The seven-day period must end yesterday; last week
   must be the previous Monday–Sunday. Check actual measurements against JSON,
   including missing values and zeros; a small model can make arithmetic errors.
   Also retry “как я спал последнюю неделю ?”. Allow several minutes for the
   full-week CPU request. Expect an answer for the seven completed days or an
   explanatory backend error, rather than the old generic proxy 504. On a
   slower machine, raise `chat.modelTimeoutSeconds` and rerun `make chat-up`.
4. Ask “How did I sleep?” or use ambiguous dates. Expect a request for a clear
   period, with no database query and no answer call. Retry with the complete
   question and explicit dates. A valid period with no rows should give a direct
   no-data explanation and retain the requested range.
5. Choose a **longer populated range whose full JSON exceeds the budget**, for
   example all available history. Send an explicit dated question for that range.
   Expect “Please choose a shorter period”, not a generic model answer. For
   this isolated request there should be just **one** Ollama `/api/chat` call
   (date extraction), not the second answer call. Inspect technical request
   logs without enabling prompt/body logging:

   ```sh
   kubectl --context kind-garmin-ai-analytics -n garmin-analytics \
     logs deployment/garmin-chat-ollama --since=2m | rg '/api/chat'
   ```

   Avoid simultaneous questions when counting requests. If the chosen range
   fits, widen it or lower the context temporarily in review settings; the guard
   is based on the actual complete request, not a fixed number of days.
6. Check cache persistence by recording the model PVC UID, model file sizes and
   manifest checksums, restarting only `deployment/garmin-chat-ollama`, and
   confirming the same files/model are present before any pull. Then ask again.
   The setup Job must not rerun just because the Pod restarted. A later Helm
   upgrade should report the model as cached.

Keep real JSON, profile IDs, credentials and answers out of commits and public
logs. No deployment or Secret changes are performed by these checks.

Date-only regression checks of the revised extractor passed against the local
`qwen2.5:1.5b-instruct-q4_K_M` model, with JSON schema, temperature 0 and context
32768. These checks sent only questions and reference-date metadata, never
Garmin summaries:

| Question | Selection |
| --- | --- |
| как хорошо я спал последнюю неделю | LAST_N_DAYS, 7 |
| как хорошо я спал | CLARIFICATION |
| How did I sleep last week? | LAST_WEEK |
| Як я спав за останні сім днів? | LAST_N_DAYS, 7 |
| How was my walking related to my sleep over the last seven days? | LAST_N_DAYS, 7 |
| как хорошо я спал недавно | CLARIFICATION |
| Как я спал на прошлой неделе? | LAST_WEEK |
| How did I sleep from 2026-10-05 to 2026-10-11? | EXPLICIT, 2026-10-05 through 2026-10-11 |

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
clarification before SQL, bound profile/date arguments, full JSON preservation,
no data, model/database failures, independent questions, and oversized requests
skipping answer calls. Budget tests include system text, JSON, question, response
reserve and non-Latin text. Configuration, React, Helm render, model setup and
Make sequencing checks remain offline. No Git write operations are performed.
