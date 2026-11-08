# Garmin AI Analytics

## Project overview

The goal is a daily AI assistant informed by Garmin watch data. This project
is in an early development phase: it currently retrieves Garmin daily summary
and sleep data, but does not yet generate AI analysis or recommendations.

### Current Airflow pipeline

The `garmin_daily_ingestion` DAG is scheduled daily. It first checks that a
valid Garmin bearer token is cached, then runs the daily-summary and sleep
ingestion tasks in parallel:

```text
Scheduled or manual DAG run
             |
             v
     ensure_garmin_token
          /       \
         v         v
 daily summary    sleep
      Pod          Pod
         \         /
          v       v
     Original JSON files
     on garmin-raw-data PVC
```

Each ingestion task runs its standalone Java job in a separate Kubernetes
Pod. Airflow supplies the processing date, coordinates dependencies, and
retries failed tasks. The jobs fetch and validate the endpoint response, then
archive the complete original JSON under a date-specific path on the
`garmin-raw-data` persistent volume. Reprocessing a date replaces that date's
file. PostgreSQL storage, analytics, and AI-generated insights are not
implemented yet.

### Intended future direction

The intended progression is to build on this reliable daily data foundation:
persist and organize the collected Garmin data, derive useful daily health and
activity context, and use that context to provide personalized AI-generated
daily summaries or assistance. These are future goals, not current
capabilities.

## Starting from scratch

### Prerequisites

Install Docker (Colima or Docker Desktop), kind, Helm, kubectl, Python 3.12,
and OpenSSL. Clone the repository and run the following commands from its
root.

### Create and validate the local environment

```sh
make cluster-create
make infra-validate
```

### Configure Garmin credentials and deploy

Create a private local credentials file from the safe template, then edit it
with your Garmin Personal API key:

```sh
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
$EDITOR config/credentials.local.json
```

`make infra-up` validates the JSON and creates the Kubernetes Secret only if
it does not already exist. Existing credentials are left unchanged; an absent
local file also preserves any existing Secret. If neither exists, deployment
continues without Garmin credentials and the auth task will not succeed until
you add the local file and run `make infra-up` again.

```sh
make infra-up
```

The bootstrap checks that the active Kubernetes context is the expected local
kind context before writing the Garmin Secret. Do not put real credentials in
the example file, manifests, source control, or logs. The local credentials
file is ignored by Git.

### Why least privilege matters

Kubernetes recommends granting users and ServiceAccounts only the permissions
they need. Its [RBAC good practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/)
warn that permission to create Pods or other workloads in a namespace can
implicitly provide access to Secrets and the permissions of ServiceAccounts
in that namespace. The long-lived API-key Secret is isolated in `garmin-auth`,
separate from Java Pods. Airflow's auth-namespace Pod permissions are narrow
and guarded by a ValidatingAdmissionPolicy that permits only the expected auth
Pod configuration. This reduces risk but is not complete isolation: Airflow
can still request the approved credential-consuming job, and cluster
administrators or compromised trusted images remain in the trust boundary.
Run `make auth-policy-verify` against a configured cluster to check effective
RBAC and admission behavior. See [Architecture — Security](docs/architecture.md#security)
for the threat model and production limitations.

### Open Airflow and run ingestion

```sh
make airflow-password
make airflow-ui
```

Sign in to the local Airflow UI as `admin` using the password printed by
`make airflow-password`. Unpause or trigger `garmin_daily_ingestion` in the
UI. A manual run can specify a `date` in `YYYY-MM-DD` form; scheduled runs use
the Airflow logical date. After the token task succeeds, summary and sleep
tasks run independently. Their complete JSON files are written to the
`garmin-raw-data` PVC.

`make infra-up` can be used again to apply changes to an existing local
cluster. Avoid `make cluster-delete` when you need to preserve the cluster's
persistent data.

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
config/
  credentials.example.json
  credentials.local.json  # local only; ignored by Git
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

## CI

`Java Integration CI` retains the `java-verify` job and **Java integration
checks** status check, and also validates Python with Ruff and pytest.
`Validate` renders Kustomize and Helm manifests without a cluster. The optional
manual **Airflow Smoke Test** remains separate. CI does not use real Garmin
credentials or personal Garmin data.

See [CONTRIBUTING.md](CONTRIBUTING.md) for development and verification
conventions.
