# Local capacity and troubleshooting

Configure and inspect the container VM used by kind. These operations affect the
running environment; follow the existing backup and maintenance instructions
before stopping services. Run commands from the repository root.

[Project overview](../README.md) · [Installation guide](getting-started.md) · [Recorded capacity check](verification/0076-local-capacity-reference.md)

## Local hardware and VM capacity

For Airflow, both PostgreSQL instances, Garmin Chat, CPU-based Ollama and two
Java ingestion Pods together, the initial recommendation is:

| Resource | Recommendation |
| --- | --- |
| Host | 8 or more logical CPUs and 16 GiB or more RAM, leaving room for the OS and IDE |
| Container VM | 4 CPUs and 8 GiB RAM, initially retaining existing workload requests/limits |

The 4-CPU/8-GiB VM passed the bounded local check below; the host recommendation
is not a verified minimum. Larger models, longer prompts and historical backfills
need separate measurement. Four CPUs do not increase the available memory.

## Colima and Docker configuration

For a new Colima profile, run `colima start --profile default --cpu 4 --memory 8`.
For an existing profile, arrange a maintenance window, finish active tasks and
[back up the databases](postgresql.md#existing-clusters-back-up-before-recreation)
before the supported stop/start resize:

```bash
docker --context colima stop --timeout 120 garmin-ai-analytics-control-plane
colima stop --profile default
colima start --profile default --cpu 4 --memory 8
docker --context colima start garmin-ai-analytics-control-plane  # Existing kind node
```

This reuses the same VM and kind container. Preserve the existing profile, disk,
runtime and mounts; do not delete/recreate the cluster for this CPU change.
Plan approximately 5–10 minutes of downtime, possibly longer during recovery.
Every container in that Colima profile is affected, including services outside
kind; gracefully stop those containers before stopping the VM, then restore
previously running containers and reconnect port-forwards afterward (`make chat-ui`
for Chat). The verified resize recovered in about 6 minutes 18 seconds.
See [Colima's resize instructions](https://github.com/abiosoft/colima/blob/v0.8.1/README.md#customizing-the-vm)
and [persistent storage](database-storage.md).

With Docker Desktop instead, configure equivalent CPU/RAM in its
[VM resource settings](https://docs.docker.com/desktop/settings-and-maintenance/settings/#resources).
kind runs inside the container VM: host CPU count alone does not determine
Kubernetes capacity. Other Docker containers also share that VM's resources.
Saved configuration alone does not prove a running VM was resized. Verify what
Docker and the node actually see after the stop/start:

```bash
docker info --format 'CPUs={{.NCPU}} RAM(bytes)={{.MemTotal}}'
kubectl --context kind-garmin-ai-analytics get nodes \
  -o 'custom-columns=NAME:.metadata.name,CPU:.status.allocatable.cpu,MEMORY:.status.allocatable.memory'
```

## CPU scheduling and troubleshooting

A CPU **request** is the amount Kubernetes budgets when scheduling and affects
CPU sharing under contention. A **limit** caps CPU consumption; it does not
reserve that amount. `1000m` equals one CPU. Placement depends on allocatable
capacity minus existing requests, even when measured usage is low.
[Kubernetes resource documentation](https://v1-33.docs.kubernetes.io/docs/concepts/configuration/manage-resources-containers/#how-pods-with-resource-requests-are-scheduled)

For `FailedScheduling: Insufficient cpu`, inspect allocation and the pending
Pod's requests/events; replace `POD_NAME` with its actual name:

```bash
make infra-resources
kubectl --context kind-garmin-ai-analytics -n airflow-jobs describe pod POD_NAME
kubectl --context kind-garmin-ai-analytics get events --all-namespaces \
  --field-selector reason=FailedScheduling --sort-by=.lastTimestamp
```

Budget for both ingestion Pods plus temporary Jobs and rollout headroom.
Lowering CPU limits or increasing retries does not fix insufficient requests
capacity. Keep workload requests/limits unchanged for the initial 4-CPU test;
subsequent tuning requires usage evidence and review. `kubectl top` needs Metrics
Server; its absence does not prevent inspecting allocation or scheduling events.
