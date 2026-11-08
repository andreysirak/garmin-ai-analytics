# Garmin AI Analytics

## Project overview

The idea is to build a daily AI assistant informed by data from a Garmin watch.
This project is in an early development phase, laying the groundwork by
ingesting Garmin Personal API data using standalone Java 25 jobs, Apache
Airflow 3, and Kubernetes (kind). The jobs currently archive complete original
JSON responses to the existing `garmin-raw-data` PVC; PostgreSQL persistence
is not implemented.

## Repository structure

The repository is organized by infrastructure, orchestration, Java ingestion,
and Python packages:

```text
infrastructure/
  kind/
  kubernetes/
  helm/
  scripts/
airflow/
  dags/
  config/
  plugins/
  tests/
garmin-integration/
  pom.xml
  garmin-common/java/
  garmin-daily-summary/java/
  garmin-sleep/java/
  garmin-auth/python/
  garmin-daily-summary/python/
  garmin-sleep/python/
```

## Java ingestion jobs

`garmin-integration/pom.xml` is the Maven parent and aggregator for:

| Module | Responsibility |
| --- | --- |
| `garmin-common/java` | Shared authenticated HTTP client and atomic raw JSON writer |
| `garmin-daily-summary/java` | Standalone `UserSummaryDailyIngestionJob` |
| `garmin-sleep/java` | Standalone `SleepDailyIngestionJob` |

Both feature modules inherit the parent POM and depend on `garmin-common`.
They produce independently executable shaded JARs. Build and verify with Java
25:

```sh
cd garmin-integration
mvn clean verify
mvn -pl garmin-daily-summary/java -am package
mvn -pl garmin-sleep/java -am package
```

`verify` runs JUnit 5, SpotBugs, PMD, and the aggregate OWASP Dependency-Check
scan. Dependency-Check fails if its vulnerability database cannot be updated
and fails for CVSS 7.0 or higher. Set `NVD_API_KEY` locally, or configure the
same GitHub Actions secret, for reliable NVD updates.

The Java programs are independent of Airflow and Kubernetes. Each requires a
token file containing an existing bearer token, supplied through
`GARMIN_TOKEN_FILE`, and the same CLI arguments:

```sh
export GARMIN_TOKEN_FILE="$HOME/.config/garmin/access-token"
java -jar garmin-integration/garmin-daily-summary/java/target/garmin-daily-summary.jar \
  --date 2026-11-07 \
  --api-base-url https://personal.connectapi.garmin.com \
  --output-dir ./data/raw/garmin
java -jar garmin-integration/garmin-sleep/java/target/garmin-sleep.jar \
  --date 2026-11-07 \
  --api-base-url https://personal.connectapi.garmin.com \
  --output-dir ./data/raw/garmin
```

Both accept `--help`. They validate the date and response, make one API
request, and write the complete unmodified response JSON to
`<output-dir>/daily-summary/YYYY-MM-DD.json` or
`<output-dir>/sleep/YYYY-MM-DD.json`. Reruns atomically replace that
date-specific file. Java never receives the long-lived API key and does not
perform token renewal.

## Python packages and verification

Feature-specific Airflow operators and the independent token manager are
packaged under:

- `garmin-daily-summary/python/` — `DailySummaryIngestionOperator`
- `garmin-sleep/python/` — `SleepIngestionOperator`
- `garmin-auth/python/` — `GarminAuthOperator` and the `garmin-auth` CLI

For combined local development and verification, create a Python 3.12 virtual
environment from the repository root and install the projects with Airflow's
constraints:

```sh
python3.12 -m venv .venv
source .venv/bin/activate
constraints="https://raw.githubusercontent.com/apache/airflow/constraints-3.3.1/constraints-3.12.txt"
python -m pip install --constraint "$constraints" \
  -e "garmin-integration/garmin-auth/python[airflow,dev]" \
  -e "garmin-integration/garmin-daily-summary/python[dev]" \
  -e "garmin-integration/garmin-sleep/python[dev]"
ruff check airflow/dags airflow/tests \
  garmin-integration/garmin-auth/python \
  garmin-integration/garmin-daily-summary/python \
  garmin-integration/garmin-sleep/python
pytest -q airflow/tests \
  garmin-integration/garmin-auth/python/tests \
  garmin-integration/garmin-daily-summary/python/tests \
  garmin-integration/garmin-sleep/python/tests
```

Tests use synthetic tokens/data and local or mocked HTTP and Kubernetes
interactions. They do not require Garmin credentials or a Kubernetes cluster.
The Airflow custom image copies all three operator packages into the DAG
processor's Python path.

## Authentication and orchestration

The auth CLI exchanges the long-lived Garmin Personal API key for a bearer
token using Garmin's Personal API token endpoint. It reads the key from
`GARMIN_API_KEY`, checks the `garmin-access-token` Kubernetes Secret, and
reuses the cached token while more than 30 minutes remain. Otherwise it
requests a new token, reads its JWT `exp`, and updates the Secret with the
token and expiration. No token is sent through Airflow XCom, command-line
arguments, or logs.

Airflow DAG `garmin_daily_ingestion` runs the following task graph:

```text
ensure_garmin_token
       ├── ingest_daily_summary
       └── ingest_sleep
```

The reusable `GarminAuthOperator` runs the auth CLI in its own Pod using the
`garmin-auth` ServiceAccount. The API key is injected from
`garmin-api-credentials`; the auth ServiceAccount can only get and update the
named `garmin-access-token` Secret. The bootstrap process creates this token
Secret before Airflow starts. The Java Pods use `airflow-job`, which has no
Secret write permissions, and mount the cached token read-only.

Scheduled runs use the Airflow logical date. Manual runs accept a `date`
parameter in `YYYY-MM-DD` form; backfills use the logical date of each
historical run. Airflow retries task failures, and the Java tasks run in
parallel only after token validation succeeds.

## Local Kubernetes and Airflow

Prerequisites: Docker (Colima or Docker Desktop), kind, Helm, kubectl,
Python 3.12, and OpenSSL. Run from the repository root:

```sh
make cluster-create
make infra-validate
```

Set `GARMIN_API_KEY` in your local shell using a secret manager or hidden
terminal prompt, then run `make infra-up`. The target builds and loads the
Airflow, auth, and ingestion images, applies Kubernetes resources, bootstraps
Secrets, and installs the Airflow chart. Unset the key afterward. Do not put
credentials in `.env`, manifests, source control, or support logs.

```sh
unset GARMIN_API_KEY
make airflow-ui
```

If `GARMIN_API_KEY` is absent during bootstrap, the cluster can still be
provisioned, but the auth task will fail until the `garmin-api-credentials`
Secret is created. Do not recreate the kind cluster or remove persistent
volumes to troubleshoot an ingestion failure.

## CI

`Java Integration CI` retains the `java-verify` job and **Java integration
checks** status check, and also validates Python with Ruff and pytest.
`Validate` renders Kustomize and Helm manifests without a cluster. The optional
manual **Airflow Smoke Test** remains separate. CI does not use real Garmin
credentials or personal Garmin data.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and verification
conventions.
