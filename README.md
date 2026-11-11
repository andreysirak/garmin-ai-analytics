# Garmin AI Analytics

**Get the most out of your Garmin watch without spending hours on settings.**

Garmin AI Analytics is a mobile app in development for everyday Garmin owners. It will use AI to explain what your metrics and activities show and suggest watch settings and activity modes that fit your routine and goals.

## What the app will do

- **Show what your watch captures.** See which metrics are recorded consistently and where readings are missing.
- **Suggest a change to try.** Get a recommendation about watch settings, syncing, or recording an activity, with an explanation grounded in your data.
- **See your activity and trends at a glance.** View daily steps, recorded workouts, and metrics such as resting heart rate and Body Battery. See how they change over time and where readings are missing.

**Understand your Garmin data and know what to try next.**

**Current status.** The mobile app is the product goal. This repository implements Garmin ingestion and the raw data archive using Java, Airflow, and Kubernetes. The first milestone is a daily-summary demo that visualizes coverage and explains one evidence-backed recommendation. See the [product vision](https://github.com/andreysirak/garmin-ai-analytics/issues/44) and [first demo](https://github.com/andreysirak/garmin-ai-analytics/issues/18).

## Architecture

```text
                  Apache Airflow 3
                         |
                 Daily Garmin pipeline
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
data type, preserving separate Pods and retries. Both tasks use the shared Python
`GarminIngestionOperator`, with explicit endpoints and date query parameters.

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
- Optional, separate local PostgreSQL database with persistent storage and deployment rollback.

The project currently **collects and archives** data; it does not yet compute health analytics or generate AI advice.

## Quick start

### Prerequisites

Install Docker (or Colima), kind, kubectl, Helm, Python 3.12, and OpenSSL. Analytics PostgreSQL also requires the local `psql` client (`brew install libpq` on macOS). Make checks find Homebrew libpq automatically, even when its `bin` directory is not on PATH. Java 25 and Maven are needed for direct Java development; Make targets can build Java code using Docker.

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
After Airflow is ready, it imports `config/connections.json` if present. An absent
file is skipped; a present but invalid file makes deployment return an error.

To deploy the separate analytics database (no Garmin credentials required):

```bash
make postgresql-up
make postgresql-loader-password
# Copy config/connections.example.json to config/connections.json,
# chmod 600 it, and manually fill in the loader password before importing.
# If Airflow is not deployed yet, make infra-up will import the file automatically.
make airflow-connections-import
make postgresql-verify
```

See [Local analytics PostgreSQL](docs/postgresql.md) for connection details,
credential handling, storage limits, and `make postgresql-rollback` scope.
The Mac endpoint is `127.0.0.1:5432`; Airflow uses the internal Service through
connection ID `garmin_analytics_loader`. Existing kind clusters without the host
mapping fail safely and require manual backup/recreation as documented.

### 4. Run the pipeline

After upgrading the Python operator package, rebuild and load the Airflow image
and restart the scheduler/DAG processor (`make kind-load-airflow-image airflow-operator-rollout`).
The existing summary/sleep image tags both contain the shared ingestion JAR.

```bash
make airflow-password
make airflow-ui
```

Sign in to Airflow as `admin` using the printed password, then trigger or unpause `garmin_daily_pipeline`. Manual runs support an optional date in `YYYY-MM-DD` format.

`garmin_daily_pipeline` is the workflow for the full ingestion-to-analytics flow;
currently its implemented stages authenticate and archive daily summary and sleep
data. Processing and analytics stages will extend this DAG. When upgrading from
`garmin_daily_ingestion`, pause the old DAG before enabling the new one. Airflow
treats the renamed DAG as a new workflow; earlier run history and archives are retained.

Java treats `--output-dir` as the **final attempt directory** and writes
`<output-dir>/<requested-date>.json`, followed by an empty `<output-dir>/_SUCCESS`
file when the write completes. It adds no endpoint, date, or download-number
subdirectories. The operator mounts the PVC at the stable `/data/raw/garmin` root.
The DAG supplies its required `output_dir` parameter as an Airflow template:

```python
output_dir=f"{MOUNT_PATH}/sleep/{ATTEMPT_PATH}",
mount_path=MOUNT_PATH,
```

The template renders this layout (`sleep` uses its own sibling directory):

```text
data/raw/garmin/
└── daily-summary/
    └── 2026-11-10/
        ├── 1/
        │   └── 2026-11-10.json
        └── 4/
            ├── 2026-11-10.json
            └── _SUCCESS
```

The DAG uses the requested date in `YYYY-MM-DD` format and the numeric
`ti.try_number`, with no run directory. On the local Mac, for example:
`/Users/andriisirak/garmin-ai-analytics/data/raw/garmin/daily-summary/2026-11-10/4/2026-11-10.json`.
The operator forwards the rendered directory unchanged to Java and XCom. The same
endpoint, date, and try number select the same directory across DAG runs; Java
rejects a nonempty attempt directory and preserves earlier files. Another download
of the same date needs a different try number.

| Task | Endpoint | Explicit query parameter |
| --- | --- | --- |
| `ingest_daily_summary` | `/usersummary-service/usersummary/daily` | `calendarDate=<requested-date>` |
| `ingest_sleep` | `/sleep-service/sleep/dailySleep` | `date=<requested-date>` |

Both run `java -jar /app/garmin-data-ingestion.jar`. The requested date comes from
run configuration, then `params.date`, then the logical date (or run start date
when no logical date is available). Each task has its own Airflow retries.

After a successful Pod exit, Python publishes the output-directory path as a
string under the task's `output_dir` XCom key. Future tasks can pull it with
`ti.xcom_pull(task_ids="ingest_sleep", key="output_dir")`. This is the same directory
passed to Java through `--output-dir`; Java creates `<requested-date>.json` and
`_SUCCESS` inside it. Downstream Pods need the same shared PVC mount and must
require `_SUCCESS` in that directory before reading JSON.

The handoff contains only the directory path. Java does not write an Airflow XCom
return file, and no XCom sidecar is used.

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
The entry point injects `FileWriterLocalImpl` and `TokenServiceKubernetesImpl` through
the `FileWriter` and `TokenService` interfaces. Their implementations live in the
shared module's `common.io.impl` and `common.service.impl` packages. The token service
reads `GARMIN_TOKEN_FILE` from the mounted Kubernetes Secret; it makes no Kubernetes
API calls.

Supply `--endpoint /path/to/resource` alongside the archive date and final output
directory. Add any number of request parameters by repeating `--query-param key=value`,
for example `--query-param from=2026-10-01 --query-param limit=25`. Omit it to send
no query parameters. Keys must be unique; values may be empty or contain `=`.
The archive `--date` is independent of request parameters and is not added automatically.
`GarminClient` uses the fixed base
URI `https://personal.connectapi.garmin.com/`; it is not a CLI argument. Java sends that
request without an endpoint registry or data-type selection. Python passes query values
unencoded as separate argument pairs, preserving empty strings, Unicode, spaces,
and additional `=` characters; Java performs URL encoding. `make java-package-ingestion` packages the shared JAR; the existing
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
- [Local analytics PostgreSQL deployment and rollback](docs/postgresql.md)
- [Kubernetes execution decision](docs/decisions/0001-kubernetes-execution.md)
- [Java ingestion and Airflow integration decision](docs/decisions/0002-java-garmin-data-ingestion.md)
- [Contributing, Story/Task workflow and PR review standards](CONTRIBUTING.md)
- [Garmin API integration retrospective](docs/retrospectives/0001-garmin-api-integration.md)
- [Changelog](CHANGELOG.md)

## Project philosophy

Build incrementally, with clear component boundaries, testable business logic, data integrity, and operational reliability. Add infrastructure only where it serves a practical need.

This is an independent learning and portfolio project, **not a medical diagnosis or treatment service**.
