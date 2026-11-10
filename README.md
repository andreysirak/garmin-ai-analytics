# Garmin AI Analytics

**A personal health data platform built with Java, Apache Airflow, and Kubernetes.**

Garmin AI Analytics is an evolving data engineering project that collects Garmin activity and sleep data, preserves original API JSON responses unchanged for later analytics and AI-powered insights.

The goal is to turn daily wearable data into meaningful personal insights while practicing production-oriented data engineering.

**Status:** Active development — Garmin ingestion is implemented; analytical processing and AI recommendations are not yet available.

## Architecture

```text
                  Apache Airflow 3
                         |
                 Daily ingestion DAG
                         |
                Garmin authentication
                  (Python auth Pod)
                         |
                 Bearer-token Secret
                         |
                +--------+--------+
                |                 |
          Daily summary         Sleep
           Java 25 Pod       Java 25 Pod
                |                 |
                +--------+--------+
                         |
                  Raw JSON archive
                   Kubernetes PVC
                         |
                 Project-local files

             Future: PostgreSQL / Beam
                         |
                  Analytics & AI
```

Airflow manages scheduling, task dependencies, and retries. Authentication runs in a restricted Kubernetes namespace; after it succeeds, daily-summary and sleep ingestion run in parallel as independent Java jobs in Kubernetes Pods.

The Java for Garmin data ingestion code has no Airflow dependency, so it can be run and tested independently.

Daily Summary and Sleep share the `garmin-integration/garmin-data-ingestion` module,
with `java/` and `python/` directories. `GarminDataIngestionJob.main()` constructs
dependencies and calls `GarminDataIngestionApp.run()`. Each invocation handles one
data type, preserving separate Pods and retries. The existing Python operator
packages are copied into the new module without behavior changes.

See [Architecture](docs/architecture.md) for component details, storage, and security boundaries.

**Why Java for ingestion?** It started as an experiment with the Airflow 3 Java Task SDK (tasks and potentially DAGs). That SDK did not offer a practical benefit for this pipeline, so Python DAGs/operators now launch independent Java JARs with CLI arguments. Java is retained deliberately for hands-on development, not because Garmin requires it. See [Java ingestion decision](docs/decisions/0002-java-garmin-data-ingestion.md).

**Why local Kubernetes instead of cloud?** Airflow is the primary orchestrator; kind runs it on a Mac during early development to avoid recurring managed-cloud costs and gain hands-on Kubernetes experience. **Cloud portability guides development from the start:** independent containers, Python DAGs and replaceable storage/configuration boundaries are preferred to local-machine assumptions. **GCP Cloud Composer and managed GCP services** are the leading migration target, although storage, IAM, secrets and execution settings will still require adaptation. See [Kubernetes vs cloud decision](docs/decisions/0001-kubernetes-execution.md) for trade-offs and migration considerations.

## Technology stack

| Layer | Technologies |
| --- | --- |
| Data ingestion | Java 25, Maven, JDK HTTP Client, Jackson |
| Orchestration | Apache Airflow 3, Python |
| Runtime | Kubernetes, kind, Docker |
| Infrastructure | Helm, Kustomize |
| Archive storage | Original JSON files, Kubernetes PV/PVC, local host mount |
| Security | Kubernetes Secrets, RBAC, ValidatingAdmissionPolicy |
| Quality | JUnit 5, pytest, Ruff, SpotBugs, PMD |
| CI | GitHub Actions |

## What's implemented

- Daily Garmin summary and sleep ingestion.
- Scheduled and manually triggered Airflow workflows.
- Independent Java ingestion jobs in separate Kubernetes Pods.
- Bearer-token acquisition, caching, and refresh.
- Unchanged raw JSON written directly to a caller-supplied final directory, after syntax validation.
- Task retries and parallel ingestion after an authentication gate.
- Dedicated authentication namespace, restricted ServiceAccounts, and admission controls.
- Java, Python, and infrastructure validation in GitHub Actions.

The project currently **collects and archives** data; it does not yet compute health analytics or generate AI advice.

## Quick start

### Prerequisites

Install Docker (or Colima), kind, kubectl, Helm, Python 3.12, and OpenSSL. Java 25 and Maven are needed for direct Java development; Make targets can build Java code using Docker.

### 1. Create the cluster

```bash
make cluster-create
make infra-validate
```

### 2. Configure Garmin credentials

```bash
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
```

Edit `config/credentials.local.json` and set your Garmin Personal API key. The file is ignored by Git. Existing Kubernetes credentials are not overwritten automatically.

### 3. Deploy Airflow and ingestion workloads

```bash
make infra-up
```

This builds and loads the local images, applies Kubernetes resources, bootstraps credentials when available, and deploys Airflow.

### 4. Run the pipeline

**DAG/XCom wiring is the next step:** the current DAG/operators still use the shared
archive root. They must supply distinct final directories for each endpoint/download attempt and
keep the PVC mount rooted at the archive root before the pipeline is run again.

```bash
make airflow-password
make airflow-ui
```

Sign in to Airflow as `admin` using the printed password, then trigger or unpause `garmin_daily_ingestion`. Manual runs support an optional date in `YYYY-MM-DD` format.

Java treats `--output-dir` as the **final attempt directory** and writes
`<output-dir>/<requested-date>.json`, followed by an empty `<output-dir>/_SUCCESS`
file when the write completes. It adds no endpoint, date, or download-number
subdirectories. A caller may select directories with this layout:

```text
data/raw/garmin/
├── daily-summary/
│   └── 2026-11-07/
│       ├── 1/
│       │   ├── 2026-11-07.json
│       │   └── _SUCCESS
│       └── 2/
│           ├── 2026-11-07.json
│           └── _SUCCESS
└── sleep/
    └── 2026-11-07/
        ├── 1/
        │   ├── 2026-11-07.json
        │   └── _SUCCESS
        └── 2/
            ├── 2026-11-07.json
            └── _SUCCESS
```

Responses are checked only for syntactically valid JSON, then saved as the original
UTF-8 string. Fields, dates, numeric formats, whitespace, and unknown properties are
not mapped or changed. Each response must contain exactly one JSON value; empty or
malformed responses are rejected before writing. Directory selection and uniqueness
belong to the caller; Java does not allocate download numbers. Each attempt requires
a new or empty directory; nonempty directories are rejected without changing their
contents. Writes go directly to a new file. A failed attempt may leave JSON behind
but receives no `_SUCCESS` marker. Downstream tasks must read only directories
containing that marker. Each retry must use a distinct directory, preserving earlier
attempts. Response schema and business validation belong downstream.

**Privacy:** Keep API keys, bearer tokens, and personal Garmin data out of Git, images, logs, and public issues. The archive directory is Git-ignored.

## Development and verification

Run Java checks with:

```bash
make java-verify
```

The executable JAR is `garmin-integration/garmin-data-ingestion/java/target/garmin-data-ingestion.jar`.
Supply `--endpoint /path/to/resource` alongside the archive date and final output
directory. Add any number of request parameters by repeating `--query-param key=value`,
for example `--query-param from=2026-10-01 --query-param limit=25`. Omit it to send
no query parameters. Keys must be unique; values may be empty or contain `=`.
The archive `--date` is independent of request parameters and is not added automatically.
`GarminClient` uses the fixed base
URI `https://personal.connectapi.garmin.com/`; it is not a CLI argument. Java sends that
request without an endpoint registry or data-type selection. The copied Python
operators must remove `--api-base-url` and pass the new arguments in their follow-up before the pipeline
is run. `make java-package-ingestion` packages the shared JAR; the existing
summary/sleep packaging targets remain aliases.

Python tests verify authentication, custom Kubernetes operators, and DAG behavior using synthetic data. See [CONTRIBUTING.md](CONTRIBUTING.md) for Python installation, Ruff, and pytest commands.

GitHub Actions runs infrastructure rendering and Java/Python checks for pull requests and `main`. A separate **Airflow Smoke Test** can be started manually; it verifies local Kubernetes/Airflow execution without accessing the real Garmin API.

Dependency vulnerability scanning is currently excluded while the NVD integration is addressed in [issue #6](https://github.com/andreysirak/garmin-ai-analytics/issues/6).

## Roadmap

- [x] Local Kubernetes and Airflow environment
- [x] Garmin authentication and Java ingestion
- [x] Raw JSON archiving with local access
- [x] Initial tests and CI workflows
- [ ] PostgreSQL storage and data normalization
- [ ] Historical backfill and data quality checks
- [ ] Apache Beam processing and derived datasets
- [ ] Sleep/activity statistics and correlations
- [ ] AI-generated daily summaries and recommendations

## Contributions welcome

Interested in Java, Python, Airflow, Kubernetes, or data engineering? Contributions are welcome, from focused bug fixes and tests to documentation improvements. Explore the [open issues](https://github.com/andreysirak/garmin-ai-analytics/issues), propose an improvement, or submit a pull request. No invitation is needed—see [CONTRIBUTING.md](CONTRIBUTING.md) for a friendly first-contribution guide and review expectations.

## Documentation

- [Architecture and security](docs/architecture.md)
- [Kubernetes execution decision](docs/decisions/0001-kubernetes-execution.md)
- [Java ingestion and Airflow integration decision](docs/decisions/0002-java-garmin-data-ingestion.md)
- [Contributing, Story/Task workflow and PR review standards](CONTRIBUTING.md)
- [Garmin API integration retrospective](docs/retrospectives/0001-garmin-api-integration.md)
- [Changelog](CHANGELOG.md)

## Project philosophy

Build incrementally, with clear component boundaries, testable business logic, data integrity, and operational reliability. Add infrastructure only where it serves a practical need.

This is an independent learning and portfolio project, **not a medical diagnosis or treatment service**.
