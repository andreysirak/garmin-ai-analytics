# 0001. Kubernetes execution model

Status: accepted

## Context
The first scaffold ran Airflow with Docker Compose and experimented with the Airflow Java Task SDK
(JavaCoordinator, Java-defined tasks). Java business logic ended up coupled to Airflow, and compute
was not isolated from the orchestrator.

## Decision
Replace it with Kubernetes (kind locally) + Helm + Kustomize. Airflow DAGs stay in Python and run
workloads with `KubernetesPodOperator`. Garmin ingestion, Beam and analytics become standalone
containers (Java 25 / Python) launched as Pods.

## Reasons
- Independent compute execution and per-job resource limits.
- Workload isolation between orchestrator and jobs.
- Portable containerized Java apps with no Airflow API dependency.
- Production-style deployment and RBAC.

## Consequences
- Docker Compose files and Java SDK settings are removed.
- Java code must expose a normal `main()` and return process exit codes.
- Extra local tooling (kind, Helm, kubectl) and RAM are required.
- The earlier experiment remains on branch `garmin-api-integration`.
