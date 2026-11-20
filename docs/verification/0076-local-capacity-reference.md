# Local capacity check recorded on 2026-11-17

This is the existing verification record relocated from the root README during
#76. Its measurements were not rerun for this documentation change. Configuration
and observations below describe that bounded check, not a current benchmark.

[Project overview](../../README.md) · [Local capacity and troubleshooting](../local-operations.md)

## Verified local configuration (2026-11-17)

The following were verified after resizing the existing profile and cluster:

| Item | Observed value |
| --- | --- |
| Host | 10 logical CPUs, 16 GiB RAM |
| Colima / Docker | Colima 0.8.1, default profile, Docker runtime, 4 CPUs, 8 GiB VM RAM, 100 GiB disk |
| kind | Same ARM64 node, Kubernetes v1.33.7; allocatable CPU 4000m, memory 8113432Ki (about 7.74 GiB) |
| Baseline requests | CPU 1925m (48%); memory 4162Mi; CPU headroom 2075m before ingestion |
| Java ingestion specification, each Pod | Requests: 100m CPU / 256Mi RAM; limits: 1000m CPU / 512Mi RAM |
| Inference workload | Cached `qwen2.5:1.5b-instruct-q4_K_M`, CPU inference, context 32768, one concurrent synthetic request capped at 512 generated tokens |
| Sampled node usage during the check | Peak CPU 3409.8m; peak memory working set 4941.5Mi (about 4.83 GiB); sampled every approximately 3 seconds |

Before the resize, 1925m of requests left only 75m on a 2000m node, preventing
either 100m ingestion Pod from fitting. After the resize, both Java containers
were observed running together during Ollama inference. The retried daily run
completed authentication, Daily Summary, Sleep and staging successfully, with no
`FailedScheduling` events for its Java Pods. Both ingestion tasks completed in
about 10.6 seconds; the synthetic inference completed with 512 generated tokens.

Before stopping Colima, full PostgreSQL 16.1/17.11 backups were restored into
isolated test containers, including roles, ownership and grants. All 71 Airflow
tables and the analytics table matched source-snapshot row counts and content
digests. Recovery preserved existing Docker containers, node/PVC identities,
backfilled rows, Ollama model files and all workload resource settings. Daily
scheduling was restored and backend/database/model health reported `UP`.

With both Java Pods, unchanged CPU requests total 2125m, leaving 1875m of
scheduling headroom. This was one daily run with bounded synthetic inference,
not a historical-backfill or long-prompt stress test. Explicit memory limits
still total 9222Mi, above node memory; limits can overcommit memory. The sampled
peak is evidence for this check, not a guarantee for larger workloads.
