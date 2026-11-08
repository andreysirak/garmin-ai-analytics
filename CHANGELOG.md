# Changelog

## Unreleased

| Type | What changed | Breaking change | Upgrade steps | PR |
| --- | --- | --- | --- | --- |
| Added | Standalone Java 25 Garmin Personal API ingestion jobs and shared raw JSON persistence | No | Build and load the summary and sleep images | |
| Added | Feature-specific Airflow KubernetesPodOperators and authentication-gated daily ingestion DAG | No | Build and load the custom Airflow and auth images | |
| Added | Python Garmin token manager with Kubernetes Secret caching, scoped auth RBAC, and synthetic test coverage | No | Configure `config/credentials.local.json` and run `make infra-up` | |
| Added | Java and Python CI verification, Ruff, pytest, and repository development guidance | No | Run `mvn clean verify` and the Python verification commands in `CONTRIBUTING.md` | |
| Docs | Clarified the project direction as a daily AI assistant informed by Garmin watch data and documented the repository structure | No | None | |
| Changed | Bootstrap Garmin credentials from an ignored, permission-restricted local JSON file without rotating existing Secrets | No | Create `config/credentials.local.json` from the example before `make infra-up` | |
| Docs | Documented Garmin credential isolation, least-privilege RBAC, admission controls, exposure risks, and production hardening | No | None | |
| Changed | Renamed Java and Python CI checks and updated the cache action to a supported release | No | None | |
| Changed | Removed OWASP Dependency-Check from Java verification after empty-key failures and unauthenticated NVD rate limiting; JUnit, SpotBugs, and PMD remain enabled | No | See [issue #6](https://github.com/andreysirak/garmin-ai-analytics/issues/6) for follow-up | |
