<a id="garmin-chat-in-kind-41"></a>

# Garmin chat

**Ask your Garmin watch.**

A React chat interface for asking questions about your stored Garmin daily
summaries. Spring Boot and Spring AI interpret supported requests, PostgreSQL
calculates the results, and a local Ollama model explains the evidence.

[Project overview](../README.md) · [Setup and deployment](docs/setup.md) ·
[Verification](docs/testing.md) · [Diagnostics](docs/chat-diagnostics.md)

## Current status

**Local demo under active development.** The implemented workflow supports
scalar summaries of steps, resting heart rate, active calories and recorded
daily-summary sleep duration. Natural-language routing and factual explanations
still need improvement and live-model verification.

Questions are independent: there is no conversation memory yet. After a
clarification, resend the complete question. Sleep quality, activity/sleep
relationships, trends and period comparisons are not supported by this flow.
Recorded daily-summary sleep duration is not verified overnight sleep or a
sleep-quality score.

## How it works

1. Spring AI sends the question, metric catalog and server reference date/timezone
   to Ollama. The model selects `ANALYZE`, `CLARIFY` or `UNSUPPORTED`.
2. Java validates that decision. Clarifications and unsupported requests return
   without querying the database.
3. For an accepted analysis, Java executes fixed, parameterized, read-only SQL
   for the server-configured profile. PostgreSQL returns values and coverage.
4. The model explains the compact evidence. If no numeric readings are available,
   the backend returns a no-data answer without a second inference call.

The model does not generate SQL. Raw summaries and profile IDs stay on the
server. Missing readings remain distinct from a recorded zero. Schema-valid
routing and an evidence prompt do not by themselves establish a correct answer.

See the [analytics reference](docs/analytics-reference.md) for parameter validation,
date semantics, coverage categories, deadlines, context budgeting and package
responsibilities. The broader [planned AI workflow](../README.md#planned-ai-workflow)
includes further answer checks that are not implemented yet.

## Supported questions and data

| Data | Unit | Calculations |
| --- | --- | --- |
| Daily steps | steps | Total, average, minimum, maximum |
| Resting heart rate | bpm | Average, minimum, maximum |
| Active energy | kcal | Total, average, minimum, maximum |
| Recorded daily-summary sleep duration | hours, converted from seconds | Total, average, minimum, maximum |

For example, “How many steps did I take last week?” requests a supported
calculation; “What was my average resting heart rate yesterday?” does too.
Use a period with stored readings when checking results. The maximum query range is configurable; see the [configuration reference](docs/configuration.md). “Last week” is the previous Monday–Sunday; the
last N days excludes today.

“How productive was I?” should prompt clarification rather than silently
substitute steps. “Do days with more physical activity tend to be followed by
better-quality sleep?” remains an unsupported analytical goal, not a demo
capability. The model does not yet handle every phrasing reliably.

## Runtime and technology

The existing kind cluster runs three single-replica Deployments in
`garmin-analytics`, alongside the existing analytics database:

| Component | Responsibility |
| --- | --- |
| React / Nginx | Serves the production UI and proxies same-origin `/api` requests. |
| Spring Boot / Spring AI | Validates HTTP requests, orchestrates routing/SQL/explanation and connects to internal Services. |
| Ollama | Runs the local CPU model and caches downloaded model files on a PVC. |
| Existing PostgreSQL | Provides daily-summary JSONB staging through a dedicated read-only login. |

The backend uses Java 25, Spring JDBC, Bean Validation and Actuator; the frontend
uses React, Vite and Nginx. A custom Helm chart manages the chat components.
The pinned default model is quantized Qwen 2.5 1.5B Instruct; model and resource
settings are configurable. See [configuration and prerequisites](docs/setup.md#configuration-and-prerequisites)
and the [configuration reference](docs/configuration.md) before choosing a larger model or context window.

## Run locally

1. Complete the [project installation](../docs/getting-started.md) and load
   daily-summary records into PostgreSQL.
2. Follow [chat setup](docs/setup.md): check prerequisites, create or reuse the
   read-only login, save its password and profile in private configuration, and
   create or update the Kubernetes Secret as documented.
3. Run `make chat-up`. It builds/loads app images, deploys the Helm release,
   waits for model initialization and starts frontend forwarding.

Open the address printed by `kubectl`, normally **http://127.0.0.1:5173**.
Keep that terminal open. Ctrl+C stops forwarding; the Pods keep running.
Use `make chat-ui` to reconnect without rebuilding or deploying again.

Deployment and model initialization details, timeout settings, Secret migration
instructions and readiness commands are in the [setup guide](docs/setup.md).
The model cache survives Pod replacement; kind's node-local model storage does
not survive cluster deletion. See the [database storage guide](../docs/database-storage.md)
for the separate persistence behaviour of PostgreSQL.

## Quality and evaluation

Use the [verification guide](docs/testing.md) for offline backend/frontend/chart
checks, optional read-only PostgreSQL checks and the manual application checklist.
Synthetic fixtures and mocked model responses verify code behaviour; they do
not measure real-model answer quality.

The [controlled evaluation](../docs/evaluations/2026-11-19-garmin-chat/README.md)
records model failures and grading caveats. The [permanent AI evaluation suite](../ai-evaluation-framework/README.md) has versioned synthetic
cases, actual production prompt export, explicit review and baseline comparison.
Read the [test-case catalogue](../ai-evaluation-framework/TEST-CASES.md), then run
`make chat-eval-test` for offline checks. Use
`make chat-eval-model EVAL_OUTPUT=data/evaluations/NEW-RUN` for real-model
routing/explanation evaluation. Application-path captures use
`make chat-eval-application` against an explicitly isolated synthetic deployment;
see the suite guide for prerequisites and limitations.

## Next steps

- Improve routing and grounded answers ([#66](https://github.com/andreysirak/garmin-ai-analytics/issues/66)).
- Benchmark and improve response time ([#67](https://github.com/andreysirak/garmin-ai-analytics/issues/67)).
- Establish repeatable evaluation ([#74](https://github.com/andreysirak/garmin-ai-analytics/issues/74)).
- Add bounded conversation memory ([#61](https://github.com/andreysirak/garmin-ai-analytics/issues/61)).

See the [project board](https://github.com/users/andreysirak/projects/2) for tracked
work. This chat is not a medical diagnosis or treatment service.

## Documentation

| I want to… | Read |
| --- | --- |
| Choose model, limits and timeouts | [Configuration reference](docs/configuration.md) |
| Configure credentials and deploy | [Setup guide](docs/setup.md) |
| Understand SQL, evidence and orchestration | [Analytics reference](docs/analytics-reference.md) |
| Run offline, SQL or manual checks | [Verification guide](docs/testing.md) |
| Capture a request trace | [Diagnostics](docs/chat-diagnostics.md) |
| Contribute | [Contribution guide](../CONTRIBUTING.md) |

<details>
<summary>Links for existing chat README bookmarks</summary>

Detailed sections have moved; earlier links remain available here.

<a id="configuration-and-prerequisites"></a>

- [Configuration and prerequisites](docs/setup.md#configuration-and-prerequisites)

<a id="reader-login-and-secret-manual-setupreview"></a>

- [Reader login and Secret (manual setup/review)](docs/setup.md#reader-login-and-secret-manual-setupreview)

<a id="deploy-and-open-for-human-review"></a>

- [Deploy and open (for human review)](docs/setup.md#deploy-and-open-for-human-review)

<a id="analytics-selection-contract"></a>

- [Analytics selection contract](docs/analytics-reference.md#analytics-selection-contract)

<a id="postgresql-aggregation"></a>

- [PostgreSQL aggregation](docs/analytics-reference.md#postgresql-aggregation)

<a id="analytics-execution-evidence-and-context-budget"></a>

- [Analytics execution, evidence and context budget](docs/analytics-reference.md#analytics-execution-evidence-and-context-budget)

<a id="backend-packages"></a>

- [Backend packages](docs/analytics-reference.md#backend-packages)

<a id="step-1-manual-verification-deployment-requires-approval"></a>

- [Step 1 manual verification (deployment requires approval)](docs/testing.md#step-1-manual-verification-deployment-requires-approval)

<a id="offline-verification"></a>

- [Offline verification](docs/testing.md#offline-verification)

</details>
