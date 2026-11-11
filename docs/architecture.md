# Architecture

**Environment:** This is a local development deployment running on the developer's Mac, not a production-grade or always-on service. See [Kubernetes execution decision](decisions/0001-kubernetes-execution.md) for why local kind was chosen over managed cloud services and what a GCP Cloud Composer migration would require.

```
kind cluster "garmin-ai-analytics"
├── namespace airflow
│   ├── api-server (UI, NodePort 30080 -> localhost:8080)
│   ├── scheduler (LocalExecutor, runs tasks)
│   ├── dag-processor
│   └── postgresql (Airflow metadata, PVC)
├── namespace garmin-auth
│   ├── garmin-api-credentials Secret
│   ├── garmin-auth ServiceAccount
│   ├── auth ConfigMap and ValidatingAdmissionPolicy
│   └── auth Pod (GarminAuthOperator)
├── namespace airflow-jobs
│   ├── garmin-access-token Secret
│   └── ingestion Pods (ServiceAccount airflow-job)
│       ├── read-only garmin-access-token Secret mount
│       └── garmin-raw-data PVC (daily summary and sleep JSON)
└── namespace garmin-analytics (optional: make postgresql-up)
    ├── garmin-postgresql StatefulSet, NodePort Service and headless Service (Bitnami chart)
    ├── garmin-postgresql password Secret
    └── garmin-postgresql-data PVC (garmin_analytics database)
```

- Kustomize (`infrastructure/kubernetes`): namespaces, ServiceAccount, RBAC, volumes.
- Helm (`infrastructure/helm/airflow`): Airflow chart 1.22.0, Airflow 3.3.1, bundled PostgreSQL subchart.
- Separate Bitnami PostgreSQL chart 18.12.18, configured through
  `infrastructure/helm/postgresql/values.yaml` with the official PostgreSQL 17 image;
  its PVC and Secret are managed outside Helm to preserve them across rollback.
  See [deployment and rollback](postgresql.md) for commands and storage limits.
- The analytics Service is exposed to the Mac as `127.0.0.1:5432` through kind
  host port 5432 / NodePort 30432. A read-only preflight rejects older clusters
  without this mapping before deployment changes. Airflow connects internally
  using `garmin_analytics_loader` as `garmin_loader`; ownership is held by the
  NOLOGIN `garmin_owner`, with setup/restore reserved for the bootstrap admin.
- DAGs: `airflow/dags` is mounted from the host into the kind node and into Airflow.
- `garmin_daily_pipeline` owns the daily workflow from ingestion through future
  analytics stages. It currently runs token validation before either independent
  Java ingestion task.
- The auth Pod runs in `garmin-auth`; its ServiceAccount may get and update only
  the named `garmin-access-token` Secret in `airflow-jobs`.
- Airflow has a separate, namespace-scoped Pod-launcher Role in both job
  namespaces. Pod creation in `garmin-auth` is additionally constrained by a
  ValidatingAdmissionPolicy.
- `airflow-job` has no Secret write permissions; Java Pods receive a read-only token file.
- Airflow images include the Garmin Python operator packages.

Implemented ingestion flow: Garmin Personal API key -> cached bearer token ->
daily summary/sleep JSON -> syntax validation -> unchanged raw JSON files on
`garmin-raw-data`.
The analytics PostgreSQL instance can be deployed separately; schema creation,
loading, and downstream analytics are not yet implemented.

## Java ingestion boundaries

`garmin-integration/garmin-data-ingestion` contains the `java/` application and
`python/` operator package. `garmin_data_ingestion.GarminIngestionOperator` shares
the Kubernetes configuration for two independent DAG task instances. Each supplies
its endpoint and an explicit date query parameter: Daily Summary uses
`calendarDate`, while Sleep uses `date`. Both launch
`java -jar /app/garmin-data-ingestion.jar` with the existing image tags.

`GarminDataIngestionJob.main(args)` constructs `TokenServiceKubernetesImpl`, a JDK
`HttpClient`, and `FileWriterLocalImpl`, then calls `GarminDataIngestionApp.run(args)`.
The application receives the `common.service.TokenService` and `common.io.FileWriter`
interfaces through its constructor. Concrete implementations live in
`common.service.impl` and `common.io.impl`; only the entry point selects them.
Dependency construction remains explicit, without a DI framework.
The application owns CLI processing, token reading, fetching, JSON syntax checking,
writing the unchanged response, and marking the attempt complete. It closes the HTTP
client on success, help, and failure. The 10-second connection and 30-second request
timeouts are preserved.

Each invocation fetches the caller-supplied `--endpoint` path. The caller may
supply zero or more `--query-param key=value` options. `GarminApiIntegrationOptions`
holds them as a `Map<String, String>`, passed directly to `GarminClient.callGet`.
Keys must be nonblank and unique. Values remain opaque strings: empty values,
Unicode, spaces, and additional `=` characters are preserved and URL-encoded when
the request is built. Parameter order is retained. The archive `--date` is used
for the filename, independently of all request parameters.
`GarminApiIntegrationCliUtil` parses and validates all arguments and returns
ready-to-use options. Endpoint validation checks HTTP path syntax; it does not
recognize or restrict Garmin endpoints. Java has no endpoint registry, data-type
selection, or image-provided request defaults. Both existing image tags build
the same shared JAR. Their legacy JAR paths remain compatibility aliases; the
Python operator uses the shared JAR path directly.
Authentication continues to gate independent Sleep and Daily Summary tasks,
each with its own Airflow retries. Java performs no retries.

`GarminClient` owns the fixed base URI `https://personal.connectapi.garmin.com/`.
It combines that URI with the caller-supplied endpoint path. There is no runtime
base URL option. `GarminClient.callGet` resolves the endpoint against `BASE_URI`
and encodes the query parameters. CLI and token-file validation run before the
client is called, so the client does not repeat input checks or normalization.
HTTP tests mock the injected JDK `HttpClient` and capture the complete request;
there is no alternate-URL constructor or application client factory. Application
tests mock `TokenService` and can inject a failing `FileWriter`. Implementation
tests exercise mounted-file token loading and local writes using temporary files.

Apache Commons CLI handles required `--date`, `--output-dir`, and `--endpoint`,
optional repeatable `--query-param`, and standalone `--help`. Required-option, argument,
duplicate, blank, date, endpoint-path, and positional-argument checks happen before
HTTP requests. `TokenServiceKubernetesImpl` implements `TokenService` by reading the
bearer token from `GARMIN_TOKEN_FILE`, which Kubernetes provides through a read-only
Secret mount. It reads the mounted file without calling the Kubernetes API. Token
trimming, validation, and failure causes are preserved. Help requires neither a token
nor request arguments. Authentication and HTTP request semantics are unchanged.
The Python operator supplies the endpoint and query parameters as separate CLI
arguments without URL encoding. There is no Python base-URL setting.

`JsonValidationUtil` uses Jackson Core to check for exactly one complete JSON
value. Objects, arrays, scalars, and JSON `null` are accepted. There are no response
DTOs, field checks, response-date checks, or sleep-data classification.

`--output-dir` remains the final attempt directory. The application writes the original
UTF-8 response to `<output-dir>/<requested-date>.json` without adding subdirectories.
`FileWriterLocalImpl` implements `FileWriter` using Java NIO. It creates parent
directories and writes the unchanged UTF-8 response directly with `CREATE_NEW`.
The application accepts only a new or empty attempt directory. Existing attempts
are never overwritten or deleted. There are no temporary files or atomic moves.
Response whitespace, unknown fields, timestamps, nulls, and numeric representations
remain unchanged.

After syntax validation and a successful JSON write, the application creates an
empty `_SUCCESS` file in the same directory. A JSON write or marker-creation failure
propagates as an ingestion failure. Failed attempts remain unmarked and may contain
partial or complete JSON files. Downstream tasks must ignore directories without
`_SUCCESS`. Directory naming and uniqueness belong to the caller; each invocation,
including retries, must receive its own directory. Java does not allocate attempt
numbers or perform downstream reads.

## Python attempt allocation and handoff

The PVC stays mounted at `/data/raw/garmin` (`mount_path` in the operator),
independently of the final `--output-dir`. The operator requires a templated
`output_dir` parameter and forwards its rendered value unchanged. Path selection
belongs to `garmin_daily_pipeline`, which supplies:

```text
<mount_path>/<daily-summary|sleep>/<requested-date>/<try_number>
```

The DAG's `ATTEMPT_PATH` template uses `PROCESSING_DATE` and `ti.try_number`.
The requested date supports run configuration, parameters, and the logical-date/
start-date fallback, formatted as `YYYY-MM-DD`. Java writes `<requested-date>.json`
and `_SUCCESS` directly in the numbered directory. The layout has no run directory.
The same endpoint/date/try selects the same path across DAG runs; Java rejects a
nonempty attempt directory without overwriting its contents. Another download of
the same date requires a different try number.

The operator validates that the rendered path is absolute and lexically contained
within `mount_path`, normalizing `.` and `..` for the containment check. It then
forwards the original path unchanged. Validation does not inspect the scheduler
filesystem or resolve symlinks in the Pod filesystem. Java creates the directory
and persists its response. Earlier failed and successful attempts are retained,
including legacy layouts.

The operator runs synchronously and disables Pod reattachment. It uses the
DAG-supplied directory without allocating an alternative path when that directory
is occupied. Airflow alone decides retries; Pod failures propagate and no archive
XCom is published for a failed execution.
The existing namespace, ServiceAccount, Secret/file access, resources, security
settings, logging, startup timeout, and execution timeout are preserved.

After successful completion, Python calls
`ti.xcom_push(key="output_dir", value=self.output_dir)`. This string is the same
final attempt directory supplied to Java. Java owns creation of `<date>.json` and
`_SUCCESS` within that directory; Python does not construct or publish their paths.
The provider may also publish its normal Pod name/namespace metadata. Java never
writes an Airflow XCom return file, and `do_xcom_push=False` disables the provider's
XCom sidecar. Future downstream tasks pull `output_dir` from the appropriate
ingestion task, mount the same PVC root, and **require `_SUCCESS` before reading
JSON**. An unmarked directory can contain partial or complete JSON; neither file
existence nor the presence of metadata proves completion. No downstream reader or
analytics is added here.

`GarminApiException`, `InvalidResponseException`, and `IngestionExecutionException`
distinguish HTTP/transport, JSON-syntax, and configuration/archive failures.
Exceptions propagate out of `run` and `main`; the JVM produces a nonzero exit
status. No code assigns exit codes or calls `System.exit`. Uncaught exceptions
use the JVM's default stack trace; there is no custom exception handler or trace
copying utility.
JSON and CLI parsing report controlled errors without attaching parser exceptions,
whose messages can contain private payloads or argument values. HTTP/transport,
token-file, and archive failures retain their ordinary causes. Never include
payloads or credentials in exception messages or logs.
Token-file validation rejects non-ASCII and whitespace characters inside a token
before the HTTP client can quote an invalid Authorization value in an exception.

## Security

### Threat model and trust boundaries

The local cluster is intended for development. The Garmin API key and bearer
token are credentials; Garmin health/activity data in the PVC is sensitive
personal data. The design separates credential handling from Java ingestion:
the trusted bootstrap operator provisions the long-lived API key in
`garmin-auth`; the auth Pod exchanges it for a short-lived bearer token and
writes that token to a specific Secret in `airflow-jobs`; Java Pods receive
only a read-only file containing the bearer token.

The Airflow scheduler is trusted to run reviewed DAG/operator code, but it is
also allowed to create and observe task Pods. Anyone able to change trusted
DAG code, the auth image, Kubernetes policies, namespace labels, or cluster
RBAC can undermine this separation. Namespace boundaries and admission
policies reduce risk; they do not protect against cluster administrators,
node-level compromise, or a compromised trusted scheduler or auth image.

### Local credential bootstrap

Copy `config/credentials.example.json` to the ignored
`config/credentials.local.json`, set its permissions to `0600`, and place the
API key in the `garmin.api_key` field. `make infra-up` validates the JSON,
checks the expected Kubernetes context, and streams the key to `kubectl` stdin
to create `garmin-api-credentials` in `garmin-auth`. It does not write a
temporary plaintext manifest or include the key in command arguments or
normal output. If the file is absent, any existing Secret is preserved. If
the Secret already exists, bootstrap does not rotate or overwrite it.

The config file is local plaintext and remains sensitive despite its file
permissions and Git ignore rule. Protect the workstation, backups, and editor
swap files; do not commit, copy into an image, or attach it to logs/issues.
Kubernetes Secret values are base64-encoded in API representations, not
encrypted merely by being stored as a Secret.

### Namespace and credential separation

`garmin-api-credentials`, the `garmin-auth` ServiceAccount, auth ConfigMap,
and authentication Pod are in `garmin-auth`. The API key is injected into the
auth container from the named Secret; the token manager needs it to call
Garmin. Neither Java ingestion Pod references that Secret or receives the
long-lived API key.

The bearer-token Secret stays in `airflow-jobs`, alongside the summary and
sleep Pods and the raw-data PVC. The Java Pods mount only the
`access-token` key read-only and use the `airflow-job` ServiceAccount, which
has no Secret write permissions and does not automount a Kubernetes API
token.

### RBAC and Pod-creation controls

The `garmin-token-manager` Role in `airflow-jobs` grants the `garmin-auth`
ServiceAccount only `get` and `update` on the named `garmin-access-token`
Secret. It does not grant create, list, watch, delete, or access to other
Secrets. Bootstrap must create the token Secret before authentication runs;
the token manager's create-if-missing code path cannot succeed under this
Role if that bootstrap prerequisite is missing.

The Airflow scheduler has a namespace-scoped Pod launcher Role in
`airflow-jobs` for the Java tasks and a separate Role in `garmin-auth` for
auth Pod creation and monitoring. Kubernetes warns that workload creation
can imply access to namespace Secrets and ServiceAccounts through the Pods
that a principal can launch; see
[RBAC good practices](https://kubernetes.io/docs/concepts/security/rbac-good-practices/#workload-creation).
Therefore, the API-key Secret is not placed in `airflow-jobs`.

The `garmin-auth-pod-restrictions` ValidatingAdmissionPolicy is selected by a
label on the `garmin-auth` namespace and bound with `Deny` validation. On Pod
creation it requires the Airflow scheduler identity and constrains the
ServiceAccount, single container, local image and command, API-key Secret
reference, auth ConfigMap, and restricted security context. It allows only the
read-only projected Kubernetes API-token mount injected for the
`garmin-auth` ServiceAccount. A namespace LimitRange applies the token
manager's CPU and memory requests/limits and bounds them to the approved values.
It rejects extra containers, volumes, host access, privileged settings, and
other Secret references. The accompanying
`infrastructure/scripts/verify_garmin_auth_admission.py` script checks RBAC
with `kubectl auth can-i` and submits an allowed Pod and hostile variants as
server-side dry runs; run it with `make auth-policy-verify`.

This is a meaningful restriction on what Airflow can launch, not a complete
security boundary. Airflow can still request the approved authentication
workload, which consumes the Garmin credential. The policy is limited to Pod
creation and depends on cluster administrators protecting the policy,
binding, namespace label, RBAC, and local image. The development image uses a
mutable local tag, not a content-pinned digest. Admission and effective RBAC
must be verified in the target cluster; successful manifest rendering alone
does not prove enforcement.

### Token handling, exposure, and rotation

The auth Pod reuses a cached bearer token while it remains valid beyond the
30-minute safety margin. Otherwise it authenticates and updates the token
Secret, including the expiration timestamp. Airflow does not pass tokens via
XCom, command-line arguments, or task logs. The ingestion Pods receive the
token only through a read-only Secret volume; the auth application avoids
logging credentials.

Kubernetes administrators and principals with relevant Secret read
permissions can access Secret values. Pod creation, exec, debugging, node
access, API audit settings, backups, and control-plane storage also affect
credential exposure risk. The current bootstrap deliberately does not rotate
an existing API-key Secret: rotation must be a conscious operator action,
coordinated with Garmin and the cluster. Replacing the bearer token is handled
by the token manager as it approaches expiry.

### Production hardening

Before using this design in a shared or production cluster, configure
Kubernetes Secret encryption at rest with managed key rotation; restrict and
audit API, node, exec, and log access; protect RBAC, admission policy and
namespace labels; use immutable, verified image digests and a trusted image
registry; harden and patch cluster nodes/control plane; and establish backup,
incident-response, and Garmin credential-rotation procedures. Review the
effective permissions and admission behavior after Kubernetes or Airflow
upgrades.
