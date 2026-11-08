# Garmin AI Analytics

Locally hosted Garmin data ingestion with standalone Java 25 jobs, Apache Airflow,
and Kubernetes (kind). The Java jobs write the complete Garmin JSON response to the
existing raw-data PVC; this MVP does not write to PostgreSQL.

## Java modules

`garmin-integration/` is a Maven multi-module build:

| Module | Purpose |
| --- | --- |
| `garmin-common` | Environment configuration, API-key authentication, HTTP client, and atomic JSON writer |
| `garmin-daily-summary` | `UserSummaryDailyIngestionJob` and daily summary response validation |
| `garmin-sleep` | `SleepDailyIngestionJob` and daily sleep response validation |

Build and run unit tests using Java 25:

```sh
cd garmin-integration
mvn clean verify
mvn -pl garmin-daily-summary -am package
mvn -pl garmin-sleep -am package
```

The verification lifecycle runs JUnit 5, SpotBugs, PMD, and the aggregate OWASP
Dependency-Check scan. The vulnerability scan updates its database and fails if
the database cannot be updated. OWASP Dependency-Check requires an NVD API key
for reliable updates; set `NVD_API_KEY` locally, or configure the GitHub Actions
secret with that name. Java CI passes it to Maven without printing it. This key
is separate from Garmin credentials.

After packaging, either job can be run with `--date YYYY-MM-DD`:

```sh
java -jar garmin-integration/garmin-daily-summary/target/garmin-daily-summary.jar \
  --date 2026-11-07
java -jar garmin-integration/garmin-sleep/target/garmin-sleep.jar \
  --date 2026-11-07
```

Jobs require `GARMIN_API_BASE_URL`, `GARMIN_API_KEY`, and
`GARMIN_RAW_OUTPUT_DIR`. `GARMIN_AUTH_URL` and timeout/retry settings are also
configurable; the Kubernetes ConfigMap supplies non-sensitive defaults.
Credentials must be supplied through the local Kubernetes Secret described below.
Never place credentials or real Garmin responses in Git.

## Container images

Build both standalone runtime images:

```sh
make docker-build
```

Load the local images into the existing kind cluster:

```sh
make kind-load-images
```

The images use a Java 25 runtime and contain only their executable shaded JAR.
`make docker-build-summary` and `make docker-build-sleep` build them separately.

## Local Kubernetes and Airflow

Prerequisites: Docker (Colima or Docker Desktop), kind, Helm, kubectl, Python 3,
and OpenSSL. Run commands from the repository root.

```sh
make cluster-create
make infra-validate
make infra-up
make docker-build
make kind-load-images
make airflow-ui
```

Kustomize provisions the `airflow-jobs` namespace, `airflow-job` ServiceAccount,
Airflow Pod-launcher RBAC, ConfigMap, and `garmin-raw-data` PVC. The Airflow Helm
chart mounts the DAG directory from the host. Both ingestion tasks use the same
single-node kind cluster and PVC.

Set the Garmin API key only in your local shell before `make infra-up`; the
bootstrap script creates `garmin-api-credentials` in `airflow-jobs` if the
variable is present. For example, set `GARMIN_API_KEY` from your local secret
manager or a hidden terminal prompt, then run `make infra-up` and unset it.
Do not put the value in `.env`, a manifest, a shell command committed to Git, or
a support log. If no key is present, infrastructure can still be deployed, but
Garmin job Pods will fail until the Secret is created.

The non-sensitive Garmin API host and authentication endpoint are configured in
`infrastructure/kubernetes/base/garmin-config.yaml`. They follow the existing
integration's API-key-to-bearer-token assumptions and should be confirmed or
adjusted to match the Garmin API credentials provisioned for your environment.

Airflow DAG `garmin_daily_ingestion` launches independent daily summary and sleep
Pods. Scheduled runs use the Airflow logical date; manual runs accept a `date`
parameter, and historical backfills use the backfill run's logical date. The two
tasks receive the same resolved date. To run the optional no-credential KPO smoke
test, use `make test-infra`. DAG unit checks are available with `make dags-test`.
For a manual run, trigger `garmin_daily_ingestion` in the Airflow UI and set its
`date` parameter to `YYYY-MM-DD`; create historical runs with Airflow's backfill
feature, which supplies the logical processing date to both tasks.

The images write deterministic files:

```text
/data/raw/garmin/daily-summary/YYYY-MM-DD.json
/data/raw/garmin/sleep/YYYY-MM-DD.json
```

Each rerun atomically replaces that resource/date file, so updated Garmin data
does not create duplicate files.

## Repository checks

- `Validate infrastructure` validates Kustomize and Helm without a cluster.
- `Airflow Smoke Test` is an optional manual kind/Airflow integration test.
- `Java Integration CI` runs the Java 25 Maven verification lifecycle on pull
  requests to `main`, pushes to `main`, and manual dispatch. It does not need
  Garmin credentials or create a Kubernetes cluster.

See [CONTRIBUTING.md](CONTRIBUTING.md) for Java style and verification details.
