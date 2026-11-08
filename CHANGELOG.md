# Changelog

## Unreleased

| Type | What changed | Breaking change | Upgrade steps | PR |
| --- | --- | --- | --- | --- |
| Added | Standalone Java 25 daily summary and sleep ingestion jobs, shared Garmin client, and atomic raw JSON persistence | No | Configure the local Garmin Secret and build/load the job images | |
| Added | KPO-based Airflow DAG, Kubernetes runtime configuration, and Java integration CI | No | Deploy the ConfigMap and ensure the local credentials Secret exists | |
