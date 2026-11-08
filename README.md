# Garmin AI Analytics

Locally hosted Kubernetes data platform for personal Garmin analytics.
Current milestone: Airflow 3 on kind, running a `KubernetesPodOperator` smoke test.
See [docs/architecture.md](docs/architecture.md) and [ADR 0001](docs/decisions/0001-kubernetes-execution.md).

## Prerequisites
Docker (Colima or Docker Desktop, ~4 GB RAM free), `kind` >= 0.33, `helm` >= 4,
`kubectl` >= 1.32, Python 3 and OpenSSL. Run commands from the repository root.
Pinned versions: Kubernetes 1.33.7 (kind node), Airflow 3.3.1, Helm chart 1.22.0.

## Architecture
The single-node kind cluster is named `garmin-ai-analytics` (context
`kind-garmin-ai-analytics`). Kustomize creates namespaces, RBAC and storage;
the official Airflow Helm chart deploys Airflow and its PostgreSQL metadata database.
The scheduler runs tasks using `LocalExecutor`, and the smoke-test task launches
a separate Pod in the `airflow-jobs` namespace. See the architecture document for details.

## Quick start
```sh
make cluster-create     # kind cluster from infrastructure/kind/cluster.yaml
make infra-up           # namespaces, RBAC, storage, secrets, PostgreSQL, Airflow
make infra-status       # check Airflow and workload Pods
make airflow-ui         # http://localhost:8080, user admin
make airflow-password   # local admin password
make test-infra         # triggers kubernetes_hello and waits for success
```
`make help` lists all targets. `make infra-validate` renders the manifests without a
cluster; Helm still needs network access to the chart repository.

## Secrets
`infrastructure/scripts/bootstrap.sh` generates random credentials directly into Kubernetes
Secrets and skips secrets that already exist. Credentials are not stored in repository files;
they persist in the local cluster. No Garmin credentials are configured yet.

## Smoke test
`airflow/dags/kubernetes_hello.py` starts a busybox Pod in `airflow-jobs`, prints Hello World,
streams logs to Airflow and deletes the Pod. Trigger it from the UI or `make test-infra`.
The Makefile target unpauses the DAG before triggering it. DAG files are mounted from
`airflow/dags` into the cluster, so they do not require an image rebuild.

## CI
The **Validate** workflow runs `make infra-validate` on every pull request targeting
`main` and every push to `main`, without a cluster. Require only the
**Validate infrastructure** status check in the `main` ruleset.

The **Airflow Smoke Test** workflow is optional and runs only when manually requested.
It creates a fresh kind cluster, deploys Airflow and waits for the smoke-test DAG run
to succeed. It uses no Garmin credentials.

To run either workflow manually, open **Actions > workflow name > Run workflow** and
select the branch to test. Both workflow files must first be present on `main` for
their manual run controls to become available.

Also run the smoke test locally on a Mac when changing kind, Docker, storage or
networking configuration: GitHub's Linux runner cannot verify Colima or Docker
Desktop behavior, the macOS host DAG mount, or localhost port mappings. Follow the
quick start above and check the UI and task logs locally.

## Troubleshooting
- Port 8080 busy: check `docker ps --filter publish=8080` and stop the process using it.
  The previous `garmin` cluster may be the owner; `docker stop garmin-control-plane`
  stops it without deleting its data. Alternatively, change `hostPort` in
  `infrastructure/kind/cluster.yaml` before creating the cluster. If changing the
  port, use the new URL manually; `make airflow-ui` still opens port 8080.
- `infra-up` says another operation is in progress: inspect
  `helm --kube-context kind-garmin-ai-analytics history airflow -n airflow` before
  deciding whether to roll back to an appropriate revision.
- Pods stuck: `make infra-status`, then `kubectl --context kind-garmin-ai-analytics -n airflow describe pod <pod>`.

## Cleanup and persistence
- `make infra-down` uninstalls Airflow only; PVCs, Secrets and the cluster remain.
- `make cluster-delete` removes the new cluster and its in-cluster data, including
  PostgreSQL data and Secrets. Host-mounted `airflow/dags` files are not deleted.
- The previous `garmin` cluster and its data are not renamed or migrated by this configuration.
  It may still occupy port 8080. The updated DAG targets `airflow-jobs`, which must be
  provisioned before it can run on any existing cluster.
- Java code from the earlier experiment lives on branch `garmin-api-integration`
  (to be migrated to `garmin-integration/` in the next milestone).
