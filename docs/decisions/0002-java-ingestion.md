# 0002. Java ingestion and Airflow integration

Status: accepted — revisit if the execution model changes.

## Context

Garmin AI Analytics originally chose Java for Garmin API integration partly to experiment with the **Apache Airflow 3 Java Task SDK**, including the possibility of implementing tasks and perhaps DAG orchestration in Java. Exploring this new capability was a learning objective, not a requirement imposed by the Garmin API.

After experimenting with the Java Task SDK, the project owner did not find a practical advantage for this pipeline. Keeping orchestration in Python was simpler and better aligned with the existing Airflow/Kubernetes setup.

## Decision

- **Python Airflow DAGs and operators** own scheduling, dependencies, retries, and launching containers.
- Airflow's `KubernetesPodOperator` launches **standalone Java 25 JARs** in Kubernetes Pods and passes runtime values as CLI arguments.
- The Java applications have **no dependency on the Airflow Java Task SDK**. They acquire Garmin API responses and archive the original JSON without DTO round-tripping; only JSON syntax is checked.
- Java remains an intentional technology choice for hands-on Java development and a standalone, independently testable ingestion component. It is **not** needed for Garmin specifically, and Python ingestion would also be viable.

## Trade-offs and consequences

This approach retains experience with Java while avoiding Airflow-specific Java code and making the ingestion containers runnable outside Airflow. The cost is maintaining Java build/images alongside the Python orchestration layer; the current data volume does not itself justify Java over Python.

Reconsider the language boundary if maintenance overhead outweighs its development value. For now, keep orchestration in Python and Java focused on Garmin HTTP ingestion. Any later changes to DAG/operator integration should be addressed separately rather than added to the current Java refactoring.

See [Kubernetes execution model](0001-kubernetes-execution.md) and [Architecture](../architecture.md).
