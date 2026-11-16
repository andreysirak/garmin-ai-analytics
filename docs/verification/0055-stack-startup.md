# Stack startup and restart (#55): local verification

Verified on 2026-11-16 without deploying, restarting, or deleting the live stack.

```bash
python3 -m unittest discover -s infrastructure/tests -v
make help
git diff --check
```

All 62 infrastructure tests passed, including 11 new tests that execute the real
Make wrappers with synthetic configuration, temporary storage markers, and fake
kind/Docker/kubectl/recursive-Make commands. They verify:

- Fresh startup and compatible-cluster reuse delegate to the existing targets in
  dependency order, including when the caller uses parallel Make.
- Restart requires an existing cluster, reuses startup, and waits for the database
  and Airflow rollouts. A failed target or rollout stops subsequent steps.
- Node image, topology, bind mount/access mode, and host-port differences stop
  before deployment or restart. Additional unsupported creation settings fail
  explicitly rather than being silently ignored.
- Missing host directories, partial storage, or missing markers are never repaired
  by automatically initializing replacement storage.
- Invalid analytics or Airflow configuration fails before inspecting the cluster.
  Inspection errors do not trigger a delete/recreate fallback.

The help output includes both new commands and preserves the existing targets.
Whitespace checks passed. No Helm values or Kubernetes manifests changed.

The actual read-only compatibility guard was also run against the current kind
node. Native kubectl decoded the checked-in kind YAML locally; kind and Docker
inspection then reported that the existing bind mounts differ from the checked-in
configuration (the node predates the database mounts). It exited nonzero with the
explicit rebuild guide, before deployment or rollout. No credentials or database
contents were read.

Full fresh startup, a successful live restart, and database/raw-archive persistence
across those operations remain deployment acceptance checks after review. The
mocked command tests establish orchestration and failure behavior; they do not
claim that a cluster migration or live restart has been completed. Follow the
[migration and explicit rebuild procedure](../database-storage.md) before changing
the existing node, coordinating verified backups with #35.

## Follow-up: scheduler startup memory

After the owner-corrected stack was started by the developer, read-only Pod
inspection found the scheduler in CrashLoopBackOff with previous exit code 137,
reason OOMKilled, and a 1 GiB memory limit. The API server, DAG processor, and
metadata database were ready. The scheduler logs included LocalExecutor startup;
Airflow 3.3.1's fork-based executor prestarts its configured worker pool.

The local values now set core.parallelism to four and the scheduler memory limit
to 2 GiB. An isolated `garmin-airflow:local` container with no network, host mounts,
tasks, or live metadata access started all four LocalExecutor workers under a
2 GiB limit, verified they remained alive, and shut them down cleanly. Both pinned
Helm charts and Kustomize overlays rendered successfully. Whitespace checks passed.
This verifies the pool configuration, not full scheduler/task memory usage. The
agent did not redeploy the live scheduler or delete any Airflow history.
