# Changelog

## Unreleased

| Type | What changed | Breaking change | Upgrade steps | PR |
| --- | --- | --- | --- | --- |
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
