# Changelog

## Unreleased

| Type | What changed | Breaking change | Upgrade steps | PR |
| --- | --- | --- | --- | --- |
| Added | Standalone Java 25 Garmin Personal API ingestion jobs and shared raw JSON persistence | No | Build and load the summary and sleep images | |
| Added | Feature-specific Airflow KubernetesPodOperators and authentication-gated daily ingestion DAG | No | Build and load the custom Airflow and auth images | |
| Added | Python Garmin token manager with Kubernetes Secret caching, scoped auth RBAC, and synthetic test coverage | No | Set `GARMIN_API_KEY` during bootstrap and run `make infra-up` | |
| Added | Java and Python CI verification, Ruff, pytest, and repository development guidance | No | Run `mvn clean verify` and the Python verification commands in `CONTRIBUTING.md` | |
| Docs | Clarified the project direction as a daily AI assistant informed by Garmin watch data and documented the repository structure | No | None | |
