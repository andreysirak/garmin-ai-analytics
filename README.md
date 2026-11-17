# Garmin AI Analytics

**Your data. Your insights. Your goals.**

A free, community-driven platform that helps Garmin users better understand their activity, recovery and health trends through data analytics and AI.

- **For Garmin users** — understand what your data means, not just see numbers
- **For developers** — contribute to a real open-source AI and data engineering platform
- **For personal development** — explore how data and AI can help people pursue measurable goals

**Current status:** Garmin AI Analytics is under active development. The first milestone is an end-to-end Garmin data and AI explanation demo; public access and contribution options are future goals. See the [product vision](https://github.com/andreysirak/garmin-ai-analytics/issues/44) and [first demo](https://github.com/andreysirak/garmin-ai-analytics/issues/18).

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

For the Spring Boot + Spring AI + Ollama + React text demo in the existing kind
cluster, see [Garmin chat](garmin-chat/README.md). `make chat-up` builds and loads
the app images, deploys the chat Helm chart, initializes its model cache, and
forwards the frontend to http://127.0.0.1:5173 in the same terminal.
The demo resolves dates from natural questions, loads complete JSON summaries
for a server-selected profile through a read-only account, and checks context
capacity before asking the model for an answer.

### Prerequisites

Install Docker (or Colima), kind, kubectl, Helm, Python 3.12, and OpenSSL. Analytics PostgreSQL also requires the local `psql` client (`brew install libpq` on macOS). Make checks find Homebrew libpq automatically, even when its `bin` directory is not on PATH. Java 25 and Maven are needed for direct Java development; Make targets can build Java code using Docker.

#### Local hardware and VM capacity

For Airflow, both PostgreSQL instances, Garmin Chat, CPU-based Ollama and two
Java ingestion Pods together, the initial recommendation is:

| Resource | Recommendation |
| --- | --- |
| Host | 8 or more logical CPUs and 16 GiB or more RAM, leaving room for the OS and IDE |
| Container VM | 4 CPUs and 8 GiB RAM, initially retaining existing workload requests/limits |

These are starting recommendations, not a verified minimum or throughput
guarantee. Larger models, longer prompts and historical backfills need separate
measurement. Four CPUs do not increase the available memory.

#### Colima and Docker configuration

For a new Colima profile, run `colima start --profile default --cpu 4 --memory 8`.
For an existing profile, arrange a maintenance window, finish active tasks and
[back up the databases](docs/postgresql.md#existing-clusters-back-up-before-recreation)
before the supported stop/start resize:

```bash
colima stop --profile default
colima start --profile default --cpu 4 --memory 8
docker --context colima start garmin-ai-analytics-control-plane  # Existing kind node
```

This reuses the same VM and kind container. Preserve the existing profile, disk,
runtime and mounts; do not delete/recreate the cluster for this CPU change.
Plan approximately 5–10 minutes of downtime, possibly longer during recovery.
Every container in that Colima profile is affected, including services outside
kind; restore previously running containers and reconnect port-forwards afterward.
See [Colima's resize instructions](https://github.com/abiosoft/colima/blob/v0.8.1/README.md#customizing-the-vm)
and [persistent storage](docs/database-storage.md).

With Docker Desktop instead, configure equivalent CPU/RAM in its
[VM resource settings](https://docs.docker.com/desktop/settings-and-maintenance/settings/#resources).
kind runs inside the container VM: host CPU count alone does not determine
Kubernetes capacity. Verify what Docker and the node actually see after resizing:

```bash
docker info --format 'CPUs={{.NCPU}} RAM(bytes)={{.MemTotal}}'
kubectl --context kind-garmin-ai-analytics get nodes \
  -o 'custom-columns=NAME:.metadata.name,CPU:.status.allocatable.cpu,MEMORY:.status.allocatable.memory'
```

#### CPU scheduling and troubleshooting

A CPU **request** is the amount Kubernetes budgets when scheduling and affects
CPU sharing under contention. A **limit** caps CPU consumption; it does not
reserve that amount. `1000m` equals one CPU. Placement depends on allocatable
capacity minus existing requests, even when measured usage is low.
[Kubernetes resource documentation](https://v1-33.docs.kubernetes.io/docs/concepts/configuration/manage-resources-containers/#how-pods-with-resource-requests-are-scheduled)

For `FailedScheduling: Insufficient cpu`, inspect allocation and the pending
Pod's requests/events; replace `POD_NAME` with its actual name:

```bash
make infra-resources
kubectl --context kind-garmin-ai-analytics -n airflow-jobs describe pod POD_NAME
kubectl --context kind-garmin-ai-analytics get events --all-namespaces \
  --field-selector reason=FailedScheduling --sort-by=.lastTimestamp
```

Budget for both ingestion Pods plus temporary Jobs and rollout headroom.
Lowering CPU limits or increasing retries does not fix insufficient requests
capacity. Keep workload requests/limits unchanged for the initial 4-CPU test;
subsequent tuning requires usage evidence and review. `kubectl top` needs Metrics
Server; its absence does not prevent inspecting allocation or scheduling events.

#### Verified local configuration (2026-11-17)

The following were inspected live, before the proposed resize:

| Item | Observed value |
| --- | --- |
| Host | 10 logical CPUs, 16 GiB RAM |
| Colima / Docker | Colima 0.8.1, default profile, Docker runtime, 2 CPUs, 8 GiB VM RAM, 100 GiB disk |
| kind | One ARM64 node, Kubernetes v1.33.7; allocatable CPU 2000m, memory 8113864Ki (about 7.74 GiB) |
| Scheduled requests | CPU 1925m; memory 4162Mi; remaining CPU 75m |
| Java ingestion specification, each Pod | Requests: 100m CPU / 256Mi RAM; limits: 1000m CPU / 512Mi RAM |

The inspected daily run had successful authentication/schema setup and failed
Daily Summary and Sleep ingestion tasks. The CPU budget explains the reported
scheduling failure: 75m cannot fit either 100m Pod. No failure events remained
when inspected. Explicit memory limits totalled 9222Mi, above node memory;
simultaneous peak memory consumption still needs verification.

The 4-CPU resize and ingestion/Ollama overlap test are **not yet verified**.
If the node advertises 4000m with unchanged requests, calculated CPU headroom
would be 2075m at baseline and 1875m with both Java Pods. These are projections,
not measured test results. No workload tests were run during this inspection.

### 1. Configure credentials once

```bash
cp config/credentials.example.json config/credentials.local.json
chmod 600 config/credentials.local.json
```

Fill in the five PostgreSQL passwords and `garmin.api_key` in this single private
file. See [credential setup](docs/credentials.md)
for the exact account mapping and adopting an existing cluster's values without
rotating them. Existing Secrets are never overwritten; conflicts name the entry
without printing its value.

For a fresh local installation, leave `"fernet_key": ""` as supplied: no key
generation is needed. Airflow then stores connection passwords and Variables
unencrypted in its metadata database. An existing encrypted installation must
keep its original key; deployment rejects an empty value that conflicts with its
live Secret. See [local Fernet behavior](docs/credentials.md#fresh-installation).

An external secrets manager is intentionally deferred to keep the local setup
simple. The private file is the current workaround for setup and rebuilds;
Kubernetes Secrets still supply runtime credentials. See
[the rationale and trade-offs](docs/credentials.md#why-this-local-workaround-exists).

### 2. Prepare the cluster

```bash
make prepare-database-storage  # First installation only; migrate existing databases first
make cluster-create
make infra-validate
```

Both PostgreSQL databases use separate host directories under `data/postgresql/`.
Make runs the databases as the host UID and checks mount identities, directory
ownership, and write permissions before deployment. Existing
node-local PVCs need a one-time backup/restore migration; keep the original
credentials and any existing Fernet key in the local file. See
[database storage and rebuild](docs/database-storage.md).

### 3. Deploy PostgreSQL and Airflow

```bash
make postgresql-up
make infra-up
```

`postgresql-up` requires only the three analytics passwords, not a Garmin API key.
It noninteractively configures the bootstrap, schema-manager, and loader accounts,
preserving existing passwords. `infra-up` uses the same file for Airflow's database
accounts, Fernet setting, Garmin API Secret, and the schema/loader connection passwords.
No password prompts, `postgresql-loader-password`, or manually duplicated
`config/connections.json` passwords are needed for normal setup or a kind rebuild.

`config/connections.example.json` defines where and as whom Airflow connects;
`config/credentials.local.json` supplies the passwords. Both Garmin PostgreSQL
definitions are checked in without password fields and used by default. Optional
`config/connections.json` (mode 0600) provides local field overrides and additional
connections. Resolved definitions are validated and sent through stdin directly
to Airflow, without generated password files. Existing entries are preserved.
See [credential handling](docs/credentials.md)
and [local analytics PostgreSQL](docs/postgresql.md) for details. The Mac endpoint
is `127.0.0.1:5432`; Airflow uses the internal analytics Service.

#### One-command alternative

After configuring `credentials.local.json` in step 1 and starting Docker/Colima,
you can replace steps 2 and 3 with:

```bash
make stack-up
```

This runs the existing storage preparation, cluster creation/reuse, manifest
validation, PostgreSQL deployment, and Airflow deployment targets in order,
including their readiness checks. It supports a fresh installation and repeat
runs. All individual targets above remain available.

To reapply configuration and restart an existing stack:

```bash
make stack-restart
```

It reuses `stack-up`, restarts both databases and the Airflow services, and waits
for their rollouts. Finish active work first because database connections are
briefly interrupted. Neither command triggers DAGs or deletes the cluster,
database files, raw archives, or existing credentials.

Both commands first check local configuration and existing kind node settings.
Changes to the node image, topology, bind mounts, or host ports require an
explicit rebuild and stop before deployment or restart. Older node-local
installations require the documented migration first. Follow the
[storage migration and rebuild guide](docs/database-storage.md#startup-and-restart-55)
when a preflight fails; the wrappers never recreate a cluster automatically.

Inspect the running cluster:

```bash
make infra-status     # Namespaces, Helm releases and Pods across all namespaces
make infra-resources  # Node capacity, Pod requests/limits, allocation totals and actual usage
```

`infra-resources` uses `kubectl top nodes` and
`kubectl top pods --all-namespaces --sort-by=memory` for actual CPU/memory usage.
This requires a working Metrics Server. If metrics are unavailable, it prints
an explanation; the capacity, requests/limits and allocation report still works.
Requests and limits are configured values; usage shows measured consumption.
Both targets use the existing kind context.

### 4. Run the pipeline

The local scheduler uses four LocalExecutor workers and a 2 GiB memory limit.
If it enters `CrashLoopBackOff` with reason `OOMKilled`, apply the checked-in
Airflow settings with `make infra-up` before running commands in its container.

After upgrading the Python operator package, rebuild and load the Airflow image
and restart the scheduler/DAG processor (`make kind-load-airflow-image airflow-operator-rollout`).
The existing summary/sleep image tags both contain the shared ingestion JAR.

```bash
make airflow-password
make airflow-ui
```

Sign in to Airflow as `admin` using the printed password, then trigger or unpause `garmin_daily_pipeline`. Manual runs support an optional date in `YYYY-MM-DD` format.

The existing `admin` user has the administrator role through SimpleAuthManager.
Admin → Configuration is enabled with `[api] expose_config=True`; Airflow 3.3.1
always masks sensitive configuration values.

To reset this DAG's Airflow metadata and run history, pause it and finish
active tasks first, then run:

```bash
make airflow-dag-reset
```

The target runs the CLI in the API container and keeps Airflow's confirmation
prompt. Select another DAG with `make airflow-dag-reset DAG_ID=other_dag`.
Airflow rejects deletion while task instances are marked running; this target
does not change their states. The Python DAG file remains and will be discovered
again. Connections, Variables, raw archives, and analytics data are preserved.

`garmin_daily_pipeline` is the workflow for the full ingestion-to-analytics flow;
currently its implemented stages authenticate and archive daily summary and sleep
data, independently create/verify the daily-summary JSONB staging schema, and
load the successful daily-summary archive after both setup and ingestion succeed.
The loader uses `garmin_analytics_loader` to upsert the complete response per
user/date, replacing the payload on reruns while preserving raw archive files.
Loading operators live in the standalone [garmin-airflow-operators](garmin-airflow-operators/README.md)
Python project; ingestion remains in `garmin-integration`. Typed SQL views remain
a follow-up step. See the
[staging contract and Connection setup](docs/postgresql.md#daily-summary-staging-contract-37).
When upgrading from `garmin_daily_ingestion`, pause the old DAG before enabling
the new one. Airflow
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
string under the task's existing `output_dir` XCom key. Current callers can pull it with
`ti.xcom_pull(task_ids="ingest_sleep", key="output_dir")`. This is the same directory
passed to Java through `--output-dir`; Java creates `<requested-date>.json` and
`_SUCCESS` inside it. Downstream Pods need the same shared PVC mount and must
require `_SUCCESS` in that directory before reading JSON.

Ingestion also publishes the full `<output-dir>/<requested-date>.json` path under
`output_json_file`. The daily-summary DAG passes
`json_file=ingest_daily_summary.output_json_file` to the loader, making the exact
file handoff visible. `processing_date` validates the payload date; the loader
opens the supplied path directly and requires an empty `_SUCCESS` in its parent.
Only paths are published, never payloads. Java does not write an Airflow XCom
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
- [Persistent database directories, migration, and cluster rebuild](docs/database-storage.md)
- [Single-file credentials, account mapping, and existing-cluster adoption](docs/credentials.md)
- [Kubernetes execution decision](docs/decisions/0001-kubernetes-execution.md)
- [Java ingestion and Airflow integration decision](docs/decisions/0002-java-garmin-data-ingestion.md)
- [Contributing, Story/Task workflow and PR review standards](CONTRIBUTING.md)
- [Garmin API integration retrospective](docs/retrospectives/0001-garmin-api-integration.md)
- [Changelog](CHANGELOG.md)

## Project philosophy

Build incrementally, with clear component boundaries, testable business logic, data integrity, and operational reliability. Add infrastructure only where it serves a practical need.

This is an independent learning and portfolio project, **not a medical diagnosis or treatment service**.
