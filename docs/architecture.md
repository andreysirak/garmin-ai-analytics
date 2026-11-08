# Architecture

```
kind cluster "garmin-ai-analytics"
├── namespace airflow
│   ├── api-server (UI, NodePort 30080 -> localhost:8080)
│   ├── scheduler (LocalExecutor, runs tasks)
│   ├── dag-processor
│   └── postgresql (Airflow metadata, PVC)
└── namespace airflow-jobs
    └── ingestion Pods launched by KubernetesPodOperator (ServiceAccount airflow-job)
        PVC garmin-raw-data (daily summary and sleep JSON)
```

- Kustomize (`infrastructure/kubernetes`): namespaces, ServiceAccount, RBAC, volumes.
- Helm (`infrastructure/helm/airflow`): Airflow chart 1.22.0, Airflow 3.3.1, bundled PostgreSQL subchart.
- DAGs: `airflow/dags` is mounted from the host into the kind node and into Airflow.
- RBAC: the scheduler may manage Pods only in `airflow-jobs`.

Implemented ingestion flow: Garmin API -> validated daily summary/sleep response -> atomic raw JSON files on `garmin-raw-data`.
PostgreSQL Bronze/Silver/Gold storage and downstream analytics remain out of scope.
