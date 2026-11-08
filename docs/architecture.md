# Architecture

```
kind cluster "garmin-ai-analytics"
├── namespace airflow
│   ├── api-server (UI, NodePort 30080 -> localhost:8080)
│   ├── scheduler (LocalExecutor, runs tasks)
│   ├── dag-processor
│   └── postgresql (Airflow metadata, PVC)
└── namespace airflow-jobs
    ├── auth Pod (GarminAuthOperator, ServiceAccount garmin-auth)
    └── ingestion Pods (ServiceAccount airflow-job)
        ├── read-only garmin-access-token Secret mount
        └── garmin-raw-data PVC (daily summary and sleep JSON)
```

- Kustomize (`infrastructure/kubernetes`): namespaces, ServiceAccount, RBAC, volumes.
- Helm (`infrastructure/helm/airflow`): Airflow chart 1.22.0, Airflow 3.3.1, bundled PostgreSQL subchart.
- DAGs: `airflow/dags` is mounted from the host into the kind node and into Airflow.
- The DAG runs token validation before either independent Java ingestion task.
- `garmin-auth` may get and update only the named `garmin-access-token` Secret.
- `airflow-job` has no Secret write permissions; Java Pods receive a read-only token file.
- Airflow images include the Garmin Python operator packages.

Implemented ingestion flow: Garmin Personal API key -> cached bearer token ->
validated daily summary/sleep responses -> atomic raw JSON files on
`garmin-raw-data`.
PostgreSQL Bronze/Silver/Gold storage and downstream analytics remain out of scope.
