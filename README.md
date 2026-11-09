# Garmin AI Analytics

**A personal health data platform built with Java, Apache Airflow, and Kubernetes.**

Garmin AI Analytics is an evolving data engineering project that collects Garmin activity and sleep data, preserves original API responses, and lays the foundation for analytics and AI-powered insights.

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

The Java ingestion code has no Airflow dependency, so it can be run and tested independently.

See [Architecture](docs/architecture.md) for component details, storage, and security boundaries.

## Technology stack

| Layer | Technologies |
| --- | --- |
| Data ingestion | Java 25, Maven, JDK HTTP Client, Jackson |
| Orchestration | Apache Airflow 3, Python |
| Runtime | Kubernetes, kind, Docker |
| Infrastructure | Helm, Kustomize |
| Raw storage | JSON files, Kubernetes PV/PVC, local host mount |
| Security | Kubernetes Secrets, RBAC, ValidatingAdmissionPolicy |
| Quality | JUnit 5, pytest, Ruff, SpotBugs, PMD |
| CI | GitHub Actions |

## What's implemented

- Daily Garmin summary and sleep ingestion.
- Scheduled and manually triggered Airflow workflows.
- Independent Java ingestion jobs in separate Kubernetes Pods.
- Bearer-token acquisition, caching, and refresh.
- Date-specific original JSON archive with atomic replacement on reruns.
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

```bash
make airflow-password
make airflow-ui
```

Sign in to Airflow as `admin` using the printed password, then trigger or unpause `garmin_daily_ingestion`. Manual runs support an optional date in `YYYY-MM-DD` format.

Successful ingestion writes files directly into the project directory:

```text
data/raw/garmin/
├── daily-summary/
│   └── YYYY-MM-DD.json
└── sleep/
    └── YYYY-MM-DD.json
```

**Privacy:** Keep API keys, bearer tokens, and personal Garmin data out of Git, images, logs, and public issues. The raw-data directory is Git-ignored.

## Development and verification

Run Java checks with:

```bash
make java-verify
```

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

## Documentation

- [Architecture and security](docs/architecture.md)
- [Kubernetes execution decision](docs/decisions/0001-kubernetes-execution.md)
- [Contributing, Story/Task workflow and PR review standards](CONTRIBUTING.md)
- [Garmin API integration retrospective](docs/retrospectives/0001-garmin-api-integration.md)
- [Changelog](CHANGELOG.md)

## Project philosophy

Build incrementally, with clear component boundaries, testable business logic, data integrity, and operational reliability. Add infrastructure only where it serves a practical need.

This is an independent learning and portfolio project, **not a medical diagnosis or treatment service**.
