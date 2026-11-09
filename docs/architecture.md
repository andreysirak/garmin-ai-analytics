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
└── namespace airflow-jobs
    ├── garmin-access-token Secret
    └── ingestion Pods (ServiceAccount airflow-job)
        ├── read-only garmin-access-token Secret mount
        └── garmin-raw-data PVC (daily summary and sleep JSON)
```

- Kustomize (`infrastructure/kubernetes`): namespaces, ServiceAccount, RBAC, volumes.
- Helm (`infrastructure/helm/airflow`): Airflow chart 1.22.0, Airflow 3.3.1, bundled PostgreSQL subchart.
- DAGs: `airflow/dags` is mounted from the host into the kind node and into Airflow.
- The DAG runs token validation before either independent Java ingestion task.
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
PostgreSQL Bronze/Silver/Gold storage and downstream analytics remain out of scope.

## Java ingestion boundaries

The existing JAR entry points delegate to endpoint-specific application `launch(args)`
methods, which install a sanitized uncaught-exception handler and call the instance
`run(args)` method. Applications parse the shared CLI options, read `GARMIN_TOKEN_FILE`,
construct dependencies, and report the result. Constructor-injected Summary and
Sleep jobs fetch responses, check JSON syntax, and archive the original strings without
logging or terminating the process. Successful execution returns normally; failures
propagate out of `main` and naturally produce a nonzero process exit status. There
are no manually assigned exit codes or calls to `System.exit`.

Shared argument parsing uses Apache Commons CLI. The existing `--date`,
`--api-base-url`, `--output-dir`, and standalone `--help` remain supported.
Commons CLI checks required options, required arguments, and unrecognized options.
Raw arguments are checked before parsing; parsed options are validated and converted
in one step into a ready-to-use `GarminApiIntegrationOptions` record.
Date/URL, duplicate, blank-value, and positional-argument rules stay explicit.
Parser exceptions retain their causes, while logged summaries exclude raw argument values.
`TokenService` owns bearer-token file reading and validation. Each CLI launcher passes
the `GARMIN_TOKEN_FILE` setting into its constructor and injects the service into the
application. Configuration is validated when the token is read, so `--help` works
without a token file.

`JsonValidationUtil` uses Jackson Core's streaming parser to check that each response
contains exactly one complete JSON value. Objects, arrays, scalars, and JSON `null`
are accepted. There are no response DTOs, field checks, response-date checks, or
sleep-data classification. CLI validation of the requested date and URL remains
separate from response validation.

Java treats `--output-dir` as the final output directory. Each job saves its response
to `<output-dir>/<requested-date>.json`. It does not append endpoint/date folders,
allocate download numbers, or select a run directory. `FileWriter.write` creates
the destination's parent directories if needed and writes the original UTF-8 string
directly using `CREATE_NEW`. Existing files are never overwritten. A failed write may
leave a partial file; there are no temporary files or atomic moves.

The caller owns directory naming and uniqueness, so it can select numbered, dated,
or run-specific directories and pass their paths downstream. Response whitespace,
unknown fields, nulls, timestamps, and numeric representations remain unchanged.
Java does not read or write XCom values or migrate existing archives.

DAG/XCom wiring remains pending for the next change. The DAG must supply distinct
final directories per endpoint/download attempt, share them through XCom, and keep the PVC mounted
at a stable archive root independently of each final output directory. The current
DAG/operator defaults are not yet updated for this Java contract; complete that
wiring before executing the pipeline.

`GarminApiException`, `InvalidResponseException`, and `IngestionExecutionException`
distinguish HTTP/transport, JSON-syntax, and configuration/archive failures.
Causes remain attached for tests and debugging. The main thread's uncaught-exception
handler logs failures once through SLF4J/Logback. `FailureLoggingUtil` copies stack
frames and exception types, but excludes third-party exception messages, JSON field
paths, and suppressed exceptions, which can contain private values. Dedicated
exception messages must contain only controlled diagnostic context; never include
payloads or credentials.
Java performs no retries; Airflow remains responsible for retry policy.

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
