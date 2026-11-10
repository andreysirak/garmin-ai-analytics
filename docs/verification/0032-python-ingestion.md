# Issue #32 verification

Initial verification on 2026-11-10 used Airflow 3.3.1, Python 3.13.14, and
`apache-airflow-providers-cncf-kubernetes` 10.21.0. This is a synthetic integration
check, not a live Garmin end-to-end test. No Garmin responses or credentials were
used. A subsequent Python 3.12 CI checkout check is recorded below.

## Automated checks

The complete relevant Python suite passed: **50 tests**. Ruff passed over the DAGs,
DAG tests, authentication package, and ingestion package. Commands inside an
isolated Airflow container with the source directories on `PYTHONPATH`:

```sh
pytest -q -p no:cacheprovider airflow/tests \
  garmin-integration/garmin-auth/python/tests \
  garmin-integration/garmin-data-ingestion/python/tests
ruff check --no-cache airflow/dags airflow/tests \
  garmin-integration/garmin-auth/python \
  garmin-integration/garmin-data-ingestion/python
```

The provider emits a deprecation warning for `reattach_on_restart=False`; this
option retains compatibility with the declared provider 10.x range. Reattachment
must remain disabled while each execution selects a fresh directory.

The updated `airflow/Dockerfile` built successfully as
`garmin-airflow:issue32-review`. Both `garmin_data_ingestion.GarminIngestionOperator`
and `garmin_auth.operator.GarminAuthOperator` imported from that image. A Python
wheel built with `pip wheel --no-deps --no-build-isolation` contained the shared
`garmin_data_ingestion` package and excluded both retired endpoint packages.

Review follow-up: after renaming the DAG to `garmin_daily_pipeline` and reducing
the XCom handoff to a single directory string under `output_dir`, all **28 DAG
and ingestion-operator tests** and the relevant Ruff checks passed.

After moving attempt-directory construction into the DAG and requiring a
templated operator `output_dir`, all **29 DAG/operator tests** and Ruff passed.
The tests cover rendered requested-date partitions, run/retry/cleared-run isolation,
unchanged caller-supplied paths, stable PVC mounts, and directory-only XCom.

## Controlled Airflow/Kubernetes check

A temporary runner Pod in the existing local kind cluster executed a copy of the
DAG using `dag.test()` with logical date `2026-11-07`,
`run_conf={"date": "2026-11-07"}`, and
`mark_success_pattern="ensure_garmin_token"`. Its Airflow metadata used an isolated
SQLite database; no live Airflow connection settings were inherited. An initial
attempt to run this inside the scheduler hit its memory limit; the successful
check used a separate runner with a 2 GiB memory limit.

The two ingestion tasks used the changed operator and real KubernetesPodOperator
execution, with these test-only substitutions:

- Authentication was marked successful; the authentication job was not run.
- A synthetic Python writer in the existing `garmin-auth:local` image replaced
  the Java command. It asserted the endpoint, explicit date query parameter, and
  archive date, then wrote fixture bytes. No HTTP requests were made.
- The Garmin token environment variable, Secret volume, and Secret mount were
  removed. The shared PVC stayed mounted at `/data/raw/garmin`; job namespace,
  service account, container identity, resource limits, and timeouts were retained.
- Archives were confined to `_verification/garmin_issue32_verify_final` below the
  archive root. The retry delay was shortened to one second and retries to one.
- The first summary attempt wrote partial JSON and exited with status 23. Its
  retry wrote complete synthetic JSON and an empty `_SUCCESS` marker.

| Task | Final state | Attempts |
| --- | --- | --- |
| `ensure_garmin_token` | Success (marked) | 1 |
| `ingest_sleep` | Success | 1 |
| `ingest_daily_summary` | Success | 2 |

Host-side checks confirmed three distinct directories: the failed summary attempt
still contains its original partial bytes without a marker, and both completed
attempts contain the expected unchanged fixture bytes and zero-byte markers.
At the time of this run, both tasks published the former `archive_paths`
dictionaries, which resolved to the completed files on the host-backed PVC. Sleep
was not rerun when summary failed. The subsequent review simplified the handoff to
one directory string under `output_dir` and moved path construction into the DAG;
the controlled cluster check above predates those changes.

Temporary runner and ingestion Pods were removed. Synthetic archive directories
were retained under the ignored `_verification` directory, including earlier
verification attempts. No Java implementation or HTTP behavior was changed or
verified against Garmin. The review image was built without rolling it out;
normal ingestion requires rebuilding/loading the Airflow image and restarting
the scheduler/DAG processor. The host-mounted DAG source already reflects local
working-tree edits.

## CI checkout completeness (Python 3.12)

Commit `c7c4319` referenced the renamed DAG and shared package, but their new
source/test files were still untracked. Consequently, a GitHub checkout contained
neither `airflow/dags/garmin_daily_pipeline.py` nor the ingestion package's `src`
directory. All six missing source, test, and verification files were staged for
the next commit.

A clean snapshot built from `git archive HEAD` plus the staged patch was checked
in a fresh `python:3.12-slim` container, independently of local untracked files
and installed packages. The workflow's Airflow constraints URL and editable-install
commands succeeded for both packages. The resolver selected Airflow 3.3.2 and the
Kubernetes provider 10.21.0. DAG `py_compile`, Ruff, and **all 51 tests** passed.
The files must be committed and pushed before GitHub Actions can use this fix.

## Review hardening follow-up

Rendered output directories are checked for lexical containment in the PVC mount
root after POSIX normalization. Regression cases reject unrelated absolute paths,
sibling prefixes, and `..` escapes before Pod execution or XCom publication; valid
paths, including custom roots, are forwarded unchanged. No Pod-filesystem symlink
resolution is attempted from the scheduler.

The Python CI job now runs an explicit DagBag import/registration step and a
separate operator/authentication step, retaining its existing status-check name.
Both steps passed locally in the Airflow 3.3.1/Python 3.13 verification image:
**18 DAG tests and 42 operator/authentication tests**, plus Ruff and workflow YAML
parsing. These follow-up checks did not rerun the constrained Python 3.12 install.

A read-only audit found that the active `main` ruleset (26791190) had no required
status-check rule. A proposal to require `Garmin API Integration Python` from the
GitHub Actions app was prepared separately; no repository ruleset was changed.

## Runtime mount-path rename follow-up (2026-11-11)

The constructor and PVC mount used `mount_path`, while the containment check still
read the removed `self.archive_root` attribute. The check, error message, custom
mount tests, and retry fixtures now consistently use `mount_path`.

All **60 Python tests** and Ruff passed. The Airflow image built as
`garmin-airflow:mount-path-fix-review`; the packaged operator executed with both
default and custom mount paths while Pod execution was mocked. No Kubernetes or
Garmin calls were made in this image check, and the image was not rolled out.
Rebuild/load the normal Airflow image and restart scheduler/DAG processor before
retrying the failed task.
