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
validated daily summary/sleep responses -> atomic raw JSON files on
`garmin-raw-data`.
PostgreSQL Bronze/Silver/Gold storage and downstream analytics remain out of scope.

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
