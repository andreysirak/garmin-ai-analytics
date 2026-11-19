# Changelog

## Unreleased

- Record the 19 November controlled Garmin chat evaluation in PR #68: 45 local
  inference calls, per-stage failures, grading caveats and sanitized per-run
  results. Keep personal evidence and raw traces private; no model comparison
  or application fix is claimed.

- Execute accepted Garmin scalar selections in Java before the explanation call,
  removing the redundant model-generated tool call. Use compact period schemas,
  constrain routing fields to the metric catalog, log validation rejection reasons,
  and clarify unsupported relationships and ambiguous goals in the routing prompt.
  Live routing quality on the configured local model still requires verification.

- Route Garmin questions with structured output and require successful tool
  evidence before completing analytical answers. Log routing and completion
  outcomes under the same request ID.

- Split Garmin chat request execution, prompt assembly, tool lifecycle and
  model-turn diagnostics into dedicated components. Configure the total chat
  deadline independently of Ollama's HTTP timeout.

- Use Spring AI tool calling for Garmin scalar analytics. Send the metric catalog
  before tool selection and return structured SQL evidence with dates, units and
  coverage. Cap each request at one tool call and require Ollama tool capability.

- Add per-request AI chat stage timings, intent validation diagnostics and opt-in
  rotating local prompt/response traces; document a repeatable model evaluation (#66).

- Preserve Airflow metadata and Garmin analytics PostgreSQL files on separate
  host-backed kind mounts; check identities, majors, and UID write permissions
  before server startup. Recreate missing database and Airflow Secrets from the
  single private setup file. Document first-install migration and explicit rebuild
  coordination with #35/#55. Existing node-local PVCs require migration (#58).

| Type | What changed | Breaking change | Upgrade steps | PR |
| --- | --- | --- | --- | --- |
| Fixed | Explain unsupported Garmin analyses and invalid calculations with a supported example; show the chat date caption only when both dates exist | No | Local UX fix; API, SQL and supported calculations are unchanged | |
| Changed | Interpret Garmin questions as validated metric queries, calculate PostgreSQL aggregates and coverage, and explain compact evidence without raw summaries; preserve recorded zeros, convert sleep seconds to hours and retain the chat API | Supports four scalar metrics; query range defaults to at most 31 days | Review the local implementation before deployment; configure chat.maxQueryDays or GARMIN_CHAT_MAX_QUERY_DAYS if needed; read-only SQL checks are documented in garmin-chat/README.md | |
| Changed | Organize Garmin chat Java classes and matching tests by controller, service, repository, model, AI and configuration responsibilities | No | None; behavior and application entry point are unchanged | |
| Added | make infra-resources shows node capacity, per-Pod requests/limits and allocation totals, plus live node/Pod usage through kubectl top when Metrics Server is available | No | Run make infra-resources; live usage requires Metrics Server | |
| Changed | make infra-status lists all namespaces, Helm releases and Pods across the existing cluster, including Garmin analytics and chat | No | Run make infra-status | |
| Fixed | Allow time for both sequential Ollama calls through the frontend proxy, make the per-call timeout configurable, and explain model timeouts after full-week CPU inference caused a 504 | No | Rebuild/redeploy with make chat-up; tune chat.modelTimeoutSeconds for slower CPUs | |
| Fixed | Date extraction keeps reference-date metadata out of the user question, emits the period type before dependent fields, and includes multilingual period examples after a Russian week question incorrectly requested clarification | No | Rebuild/redeploy with make chat-up; date-only checks passed on the configured small Ollama model | |
| Added | Garmin chat resolves structured dates with Ollama and an injected Clock/timezone, queries complete JSON for the server profile and selected period, clarifies missing dates and blocks oversized answer prompts; make chat-up deploys the stack and forwards the UI (#41) | Existing chat Secret needs a profile-id key | Review the profile Secret key, context budget and timezone before deployment; see garmin-chat/README.md | |
| Added | make airflow-dag-reset resets a DAG's Airflow metadata/history through the API container while preserving its source code; defaults to garmin_daily_pipeline with optional DAG_ID override | No | Finish active tasks before reset; Airflow keeps its confirmation prompt and running-task check; DAG code is rediscovered automatically | |
| Fixed | Bound the local LocalExecutor pool to four workers and give the scheduler a 2 GiB memory limit after observed startup OOM kills | Task concurrency is limited to four per scheduler | Apply the Airflow values with make infra-up; no metadata reset is needed | |
| Fixed | Run both local PostgreSQL instances as the host UID and check actual directory ownership before startup, preventing Colima SSHFS wrong-ownership failures | Existing data must belong to the deploying host UID | Reapply with make stack-up; no kind recreation or automatic ownership rewrite | |
| Changed | Default fresh local Airflow setup to an explicitly empty Fernet key, removing key generation; retain support and conflict protection for existing encrypted installations | New local metadata stores connection passwords and Variables unencrypted | Leave fernet_key empty for fresh setup; keep the original key for encrypted metadata; see docs/credentials.md | |
| Added | Compose existing startup targets with make stack-up and reapply/restart the existing stack with make stack-restart; check kind creation settings and retained storage before changes (#55) | No; individual targets and manual startup remain supported | Configure credentials.local.json first; creation-time changes require the documented explicit rebuild | |
| Docs | Explain the deliberate local credential-file workaround while external secrets-manager integration is deferred for simplicity, including runtime Secret delivery and future lookup replacement | No | None | |
| Fixed | Keep both Garmin connection definitions and descriptions in connections.example.json without passwords; merge optional local overrides, resolve private passwords and register through stdin without generated password files | Managed password fields in connection definitions are rejected | Keep passwords only in credentials.local.json; see docs/credentials.md | |
| Changed | Use credentials.local.json for all five PostgreSQL passwords, Garmin API key and original Airflow Fernet key; configure role passwords noninteractively, derive managed Airflow connections, reject credential conflicts and remove Secret snapshots | Existing files need the new fields with their current live values | Follow docs/credentials.md before deployment; normal setup is make postgresql-up then make infra-up | |
| Added | Daily-summary Python loader uses successful ingestion XCom, validates archive keys/date, and transactionally upserts the full JSONB payload without changing raw files (#38) | Daily runs now require garmin_analytics_loader and scheduler archive access | Apply updated Airflow Helm values through make infra-up for the read-only archive mount and host GID; see docs/postgresql.md | |
| Added | Transactional ensure_daily_summary_staging task in the daily DAG, versioned three-column full-response JSONB contract, payload-object and exact schema checks and separate schema Connection (#37) | New task requires garmin_analytics_schema; existing wide tables require a reviewed migration | Configure the schema Connection for new installations; migrate/backfill wide tables from original archives separately; see docs/postgresql.md | |
| Changed | make infra-up imports config/connections.json after Airflow readiness; absent files preserve existing connections and invalid files fail import | Local connection file becomes authoritative on infra-up | Fill in the loader password; use AIRFLOW_CONNECTIONS_FILE to override the path | |
| Fixed | Discover the native PostgreSQL client in Homebrew libpq when psql is not on PATH; use the same executable in preflight and Mac connection checks | No | Install libpq if absent; no PATH change required for Make checks | |
| Added | make postgresql-password displays the existing analytics bootstrap admin password without changing credentials | No | Loader passwords remain separately configured with make postgresql-loader-password | |
| Added | Loopback PostgreSQL access on 127.0.0.1:5432 with a read-only kind mapping preflight; version-controlled owner/loader grants and manually imported Airflow loader connection | Existing clusters need a host mapping | Back up before manual cluster recreation; set the loader password and import config/connections.local.json; see docs/postgresql.md | |
| Added | Separate local garmin_analytics database using the pinned Bitnami PostgreSQL chart, external PVC and generated Secret, Make rollback and synthetic persistence verification (#36) | No | Run make postgresql-up; see docs/postgresql.md for rollback scope and migration from the earlier custom chart | |
| Changed | Store ingestion archives as data-type/YYYY-MM-DD/try-number/YYYY-MM-DD.json, without a run directory | New attempts use the simplified layout | Existing archives remain readable in their original locations | |
| Fixed | Use mount_path consistently in ingestion containment validation and retry fixtures after the PVC setting rename | No | Rebuild/load the Airflow image and restart scheduler/DAG processor before retrying | |
| Fixed | Reject rendered ingestion directories outside the mounted archive root, including parent traversal and sibling-prefix paths; explicitly validate DAG imports and registration in Python CI | No | Configure Garmin API Integration Python as required in the repository ruleset | |
| Changed | Rename the daily DAG and source file to garmin_daily_pipeline to cover the intended ingestion-to-analytics workflow | Yes: Airflow DAG ID changed | Pause garmin_daily_ingestion before enabling garmin_daily_pipeline; previous run history and archives remain available | |
| Changed | Replace duplicated Python ingestion operators with GarminIngestionOperator; supply shared Java CLI arguments, accept DAG-templated attempt directories, and publish only the output-directory path through XCom | Yes: import garmin_data_ingestion; supply a templated output_dir and a separate mount_path; remove api_base_url | Rebuild/load Airflow image and restart scheduler/DAG processor; downstream readers must require _SUCCESS | |
| Changed | Inject FileWriter and TokenService interfaces; move local writing to common.io.impl.FileWriterLocalImpl and mounted-Secret token loading to common.service.impl.TokenServiceKubernetesImpl | No CLI or runtime configuration changes | Rebuild the Java ingestion image | |
| Fixed | Create _SUCCESS in the supplied attempt directory beside its JSON archive | No | Rebuild the Java ingestion image | |
| Added | Mark completed raw JSON ingestion attempts with an empty _SUCCESS file; reject nonempty attempt directories and preserve previous attempts | Yes: each invocation requires a new or empty --output-dir | Supply a distinct directory for every attempt, including retries; downstream readers must require _SUCCESS | #29 |
| Changed | Consolidate Sleep and Daily Summary under garmin-data-ingestion/java and python; use one injected Java application and thin main, a fixed Garmin base URI, and caller-supplied endpoint paths and generic query parameters | Yes: Java module/JAR, entry point and required arguments changed | Remove --api-base-url; pass --endpoint and optional repeatable --query-param key=value; update Python operators in their follow-up and rebuild images | |
| Docs | Document why Java ingestion was retained after evaluating the Airflow 3 Java Task SDK, while orchestration stays in Python | No | None | #29 |
| Changed | Move output-directory selection out of Java; write unchanged JSON directly to the supplied final directory and reject existing files | Yes: --output-dir is now the final destination | Update DAG/XCom paths and operator mounts before running the pipeline | |
| Changed | Remove response DTOs and mapping; verify JSON syntax with Jackson Core and persist the unchanged raw response | Yes: response DTO classes and field-level validation removed | Rebuild both Java images; perform schema and business validation downstream | |
| Changed | Refactor Java ingestion into an injected application and thin entry point, with Apache Commons CLI parsing, natural exception propagation, and SLF4J/Logback application logging | No | Rebuild and load both Java images | |
| Docs | Clarify local IDE AI coding vs browser GitHub write permissions and provide separate agent prompts | No | None | #27 |
| Fixed | Model Garmin daily summary and sleep response fields as typed Java DTOs, handle flat or wrapped sleep responses, and test ingestion/date validation with synthetic JSON | No | Verify live response structure before downstream schema mapping | |
| Docs | Set AI-assisted commit hygiene and require separate explicit consent for squash/history rewriting and force-push | No | None | #24 |
| Docs | Explain local Kubernetes choice, cloud trade-offs, and GCP-first migration considerations | No | None | |
| Docs | Define Story → Task → PR workflow, AI-assisted PR notes, and Garmin integration retrospective | No | None | |
| Added | Standalone Java 25 Garmin Personal API ingestion jobs and shared raw JSON persistence | No | Build and load the summary and sleep images | |
| Added | Feature-specific Airflow KubernetesPodOperators and authentication-gated daily ingestion DAG | No | Build and load the custom Airflow and auth images | |
| Added | Python Garmin token manager with Kubernetes Secret caching, scoped auth RBAC, and synthetic test coverage | No | Configure `config/credentials.local.json` and run `make infra-up` | |
| Added | Java and Python CI verification, Ruff, pytest, and repository development guidance | No | Run `mvn clean verify` and the Python verification commands in `CONTRIBUTING.md` | |
| Docs | Clarified the project direction as a daily AI assistant informed by Garmin watch data and documented the repository structure | No | None | |
| Changed | Bootstrap Garmin credentials from an ignored, permission-restricted local JSON file without rotating existing Secrets | No | Create `config/credentials.local.json` from the example before `make infra-up` | |
| Docs | Documented Garmin credential isolation, least-privilege RBAC, admission controls, exposure risks, and production hardening | No | None | |
| Changed | Renamed Java and Python CI checks and updated the cache action to a supported release | No | None | |
| Changed | Removed OWASP Dependency-Check from Java verification after empty-key failures and unauthenticated NVD rate limiting; JUnit, SpotBugs, and PMD remain enabled | No | See [issue #6](https://github.com/andreysirak/garmin-ai-analytics/issues/6) for follow-up | |
