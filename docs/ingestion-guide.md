# Run and develop the ingestion pipeline

This guide retains the task, archive, handoff and development details previously
in the root README. Complete the [installation guide](getting-started.md) first.
All commands run from the repository root.

[Project overview](../README.md) · [Architecture](architecture.md) · [Contribution guide](../CONTRIBUTING.md)

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
Loading operators live in the standalone [garmin-airflow-operators](../garmin-airflow-operators/README.md)
Python project; ingestion remains in `garmin-integration`. Typed SQL views remain
a follow-up step. See the
[staging contract and Connection setup](postgresql.md#daily-summary-staging-contract-37).
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

Java tests are separated by execution scope. The five isolated `garmin-common`
tests mirror their production packages and use `*Test.java` (Maven Surefire).
The ingestion application workflow and subprocess CLI checks live in the
`com.garmin.analytics.integration.it` test package and use `*IT.java`
(Maven Failsafe). These integration tests run offline with synthetic inputs;
they do not call the live Garmin API or require Kubernetes.

```bash
# Unit tests only (Surefire).
mvn -B -ntp -f garmin-integration/pom.xml test

# Unit tests, offline integration tests, and Java static checks.
mvn -B -ntp -f garmin-integration/pom.xml clean verify
```

`make java-verify` continues to run the full `clean verify` lifecycle.
Failsafe writes reports to `target/failsafe-reports` within the ingestion module;
Surefire reports remain in each module's `target/surefire-reports`.

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

Python tests verify authentication, custom Kubernetes operators, and DAG behavior using synthetic data. See [CONTRIBUTING.md](../CONTRIBUTING.md) for Python installation, Ruff, and pytest commands.

GitHub Actions runs infrastructure rendering and Java/Python checks for pull requests and `main`. A separate **Airflow Smoke Test** can be started manually; it verifies local Kubernetes/Airflow execution without accessing the real Garmin API.

Dependency vulnerability scanning is currently excluded while the NVD integration is addressed in [issue #6](https://github.com/andreysirak/garmin-ai-analytics/issues/6).
