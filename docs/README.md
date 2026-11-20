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

The permanent AI evaluation suite is tracked in [#74](https://github.com/andreysirak/garmin-ai-analytics/issues/74)
and [draft PR #75](https://github.com/andreysirak/garmin-ai-analytics/pull/75).
Its commands are not assumed to be merged into this branch.
