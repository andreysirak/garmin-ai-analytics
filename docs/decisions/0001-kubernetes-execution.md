# 0001. Kubernetes execution model

Status: accepted — local development; revisit before cloud deployment.

## Context

Airflow is the primary orchestrator for Garmin AI Analytics. The initial Docker Compose
scaffold also experimented with the Airflow Java Task SDK, but coupled Java business
logic to the orchestrator.

During early development, the project runs on the developer's **Mac**. Managed cloud
environments would introduce ongoing infrastructure costs before the first complete
sleep analytics pipeline is ready. The developer already has AWS and GCP experience
and also wants practical Kubernetes experience.

## Decision

Run **Apache Airflow 3 on local kind/Kubernetes**, deployed with Helm and Kustomize.
Keep DAGs in Python, launching standalone Java/Python containers with
`KubernetesPodOperator`. Kubernetes is a learning and execution choice, **not** a
requirement dictated by Garmin data volume.

**Cloud migration is an ongoing design constraint, starting now.** Every new
pipeline component should consider how its data access, configuration, secrets,
and execution model would work in managed cloud services. Prefer replaceable
infrastructure adapters over coupling business logic to kind, hostPath or a
particular machine. Validate portability in design and tests where practical;
do not postpone all migration concerns until deployment.

The preferred future cloud option is **GCP Cloud Composer (managed Airflow)**,
with suitable managed GCP services for storage, relational data, secrets, and
container execution. AWS remains a possible alternative.

## Trade-offs

| Option | Advantages | Limitations |
| --- | --- | --- |
| **Local kind + Airflow (chosen now)** | No managed cloud bill; Kubernetes/Helm/RBAC practice; independent containerized jobs; developer controls the stack | Uses local RAM/CPU; maintenance is manual; depends on Mac being awake; not production availability |
| **GCP Cloud Composer + GCP managed services (preferred cloud target)** | Managed Airflow operations; cloud-native storage, database, IAM and monitoring | Recurring charges even for modest workloads; cloud setup and IAM/storage changes; Composer-specific Kubernetes execution constraints |
| **AWS MWAA + AWS services (alternative)** | Managed Airflow with S3/RDS and existing AWS ecosystem | Recurring charges; AWS-specific integrations and migration work |
| **Docker Compose + Airflow** | Simpler local setup, fewer moving parts | Less Kubernetes practice; different execution model from planned containerized job deployment |

## Cloud migration: portable by design, not zero effort

- Preserve **Python Airflow DAGs** and **standalone application containers**;
  avoid embedding ingestion business logic in Airflow-specific Java APIs.
- Replace **kind hostPath/PVC raw files** with cloud object storage (for example,
  **Google Cloud Storage**) and adapt file access/replay contracts accordingly.
- Move application PostgreSQL to a managed service (for example **Cloud SQL**)
  with Flyway migrations, networking, backups and connection/security updates.
- Publish container images in a registry (for example **Artifact Registry**);
  adapt image names, permissions, deployment settings and observability.
- Map local Kubernetes Secrets, ServiceAccounts, RBAC and admission controls
  to cloud IAM and **Secret Manager**, accounting for the target managed
  Airflow/cluster security model.
- Verify the target **Cloud Composer version**, provider versions, permitted
  `KubernetesPodOperator` namespaces, execution environment and resource limits
  before committing to the deployment model.

**During each phase**, review storage contracts, Airflow/operator compatibility,
credentials, container images and deployment configuration for cloud portability.
Document intentional local-only assumptions as migration work rather than
letting them become hidden dependencies.

The goal is to **minimize application rewrites**, not to claim that hostPath
volumes, namespace rules or local infrastructure manifests migrate unchanged.

## Consequences

- Airflow stays the orchestrator; Kubernetes isolates task execution from the scheduler.
- The Mac-hosted cluster is **development-only**: scheduled runs may fail or not
  execute while the computer is asleep or the cluster is stopped.
- Extra local tooling and RAM are required compared with Docker Compose.
- Cloud hosting will be reconsidered when a useful end-to-end sleep pipeline is
  working and the benefits justify the costs.

See [Architecture](../architecture.md) for the current deployed security and
runtime details.
