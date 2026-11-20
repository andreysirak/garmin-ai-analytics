# Documentation

Start with the [project overview](../README.md). Guides explain current behaviour;
[verification records](verification/) and [evaluations](evaluations/) record dated
observations, not guarantees for the current revision.

## Run it

- [Install the local PostgreSQL and Airflow stack](getting-started.md).
- [Run the ingestion pipeline](ingestion-guide.md#4-run-the-pipeline).
- [Set up and deploy the chat](../garmin-chat/docs/setup.md).
- [Choose chat model, limits and timeouts](../garmin-chat/docs/configuration.md).
- [Configure credentials](credentials.md) and [preserve databases across rebuilds](database-storage.md).

## Develop it

- [Contribution and review workflow](../CONTRIBUTING.md).
- [Ingestion contracts and development checks](ingestion-guide.md).
- [Chat analytics, evidence and backend packages](../garmin-chat/docs/analytics-reference.md).
- [Chat code, SQL and application checks](../garmin-chat/docs/testing.md).
- [Testing terminology and documentation maintenance](documentation-maintenance.md).

## Understand it

- [Architecture and security boundaries](architecture.md).
- Decisions: [Kubernetes execution](decisions/0001-kubernetes-execution.md),
  [Java ingestion](decisions/0002-java-garmin-data-ingestion.md).
- Retrospectives: [ingestion](retrospectives/0001-garmin-api-integration.md),
  [AI chat](retrospectives/0002-ai-chat-integration.md).
- [Controlled real-model evaluation](evaluations/2026-11-19-garmin-chat/README.md).
- [Changelog](../CHANGELOG.md).

## Investigate a problem

- [Local VM resources and scheduling](local-operations.md).
- [PostgreSQL, roles and rollback](postgresql.md).
- [Storage migration and cluster recovery](database-storage.md).
- [Chat request diagnostics](../garmin-chat/docs/chat-diagnostics.md).
- Historical observations: [bounded local capacity check](verification/0076-local-capacity-reference.md)
  and [earlier chat check counts](verification/0076-chat-checks-reference.md).

## Evaluate AI changes

Use the [AI evaluation start guide](../garmin-chat/evaluation/README.md),
[test-case catalogue](../garmin-chat/evaluation/TEST-CASES.md) and
[technical reference](../garmin-chat/evaluation/REFERENCE.md) for offline contracts,
real-model trials, synthetic application captures, review and baseline comparisons.
