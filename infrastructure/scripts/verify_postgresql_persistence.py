"""Verify a synthetic row survives PostgreSQL Pod replacement and Helm rollback."""

from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
from pathlib import Path

from verify_postgresql_clients import main as verify_clients

CONTEXT = os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics")
NAMESPACE = "garmin-analytics"
RELEASE = "garmin-postgresql"
CLAIM = "garmin-postgresql-data"
ROOT = Path(__file__).resolve().parents[2]
KUBECTL = ["kubectl", "--context", CONTEXT, "--namespace", NAMESPACE]
HELM = ["helm", "--kube-context", CONTEXT, "--namespace", NAMESPACE]


def run(*arguments: str, input_text: str | None = None) -> str:
    return subprocess.run(
        arguments, input=input_text, text=True, check=True, capture_output=True,
    ).stdout.strip()


def ready_pod(previous_uid: str = "") -> dict:
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        pods = json.loads(run(
            *KUBECTL, "get", "pods", "-l", f"app.kubernetes.io/instance={RELEASE}",
            "-o", "json", "--request-timeout=10s",
        ))["items"]
        for pod in pods:
            metadata = pod["metadata"]
            ready = any(
                condition["type"] == "Ready" and condition["status"] == "True"
                for condition in pod.get("status", {}).get("conditions", [])
            )
            if ready and not metadata.get("deletionTimestamp") and metadata["uid"] != previous_uid:
                return metadata
        time.sleep(2)
    raise RuntimeError("Timed out waiting for a new Ready PostgreSQL Pod")


def sql(statement: str) -> str:
    # Read the mounted password inside the Pod, without exposing it in argv/output.
    return run(
        *KUBECTL, "exec", "-i", f"statefulset/{RELEASE}", "-c", "postgresql", "--",
        "sh", "-ec",
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; '
        f'exec psql -X -h {RELEASE} -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
        '-v ON_ERROR_STOP=1 -At',
        input_text=statement,
    )


def main() -> None:
    chart = os.environ["POSTGRESQL_CHART"]
    chart_version = os.environ["POSTGRESQL_CHART_VERSION"]
    status = json.loads(run(*HELM, "status", RELEASE, "-o", "json"))
    if status["info"]["status"] != "deployed":
        raise RuntimeError("Run make postgresql-up successfully before verification")
    revision = str(status["version"])
    pod = ready_pod()
    verify_clients()
    passwordless = subprocess.run(
        [
            *KUBECTL, "exec", f"statefulset/{RELEASE}", "-c", "postgresql", "--",
            "sh", "-ec",
            'unset PGPASSWORD; export PGPASSFILE=/dev/null; '
            f'exec psql -X -w -h {RELEASE} -U "$POSTGRES_USER" '
            '-d "$POSTGRES_DB" -c "SELECT 1;"',
        ],
        text=True, capture_output=True,
    )
    if passwordless.returncode == 0 or "no password supplied" not in passwordless.stderr:
        raise RuntimeError("Expected the database Service to reject passwordless connections")
    print("PASS: database Service requires a password", flush=True)
    pvc_uid = run(*KUBECTL, "get", "pvc", CLAIM, "-o", "jsonpath={.metadata.uid}")
    marker = uuid.uuid4().hex
    table = f"public._persistence_check_{marker}"

    def check_row(stage: str) -> None:
        if sql(f"SELECT marker FROM {table};") != marker:
            raise RuntimeError(f"Synthetic row missing after {stage}")
        if run(*KUBECTL, "get", "pvc", CLAIM, "-o", "jsonpath={.metadata.uid}") != pvc_uid:
            raise RuntimeError(f"PVC was replaced during {stage}")
        print(f"PASS: row and original PVC survive {stage}", flush=True)

    def rollback() -> None:
        print(run(
            "make", "--no-print-directory", "-C", str(ROOT), "postgresql-rollback",
            f"CONTEXT={CONTEXT}", f"POSTGRESQL_REVISION={revision}",
        ), flush=True)

    upgraded = False
    sql(f"CREATE TABLE {table} (marker text PRIMARY KEY); INSERT INTO {table} VALUES ('{marker}');")
    try:
        run(*KUBECTL, "delete", "pod", pod["name"], "--wait=true", "--timeout=120s")
        pod = ready_pod(pod["uid"])
        check_row("Pod replacement")
        verify_clients()

        # A harmless Pod-template change creates a real release revision and rollout.
        # The database image, credentials and volume stay unchanged.
        upgraded = True
        print(run(
            *HELM, "upgrade", RELEASE, chart, "--version", chart_version,
            "--reuse-values", "--set-string", f"primary.podAnnotations.persistence-check={marker}",
            "--wait", "--timeout", "5m",
        ), flush=True)
        pod = ready_pod(pod["uid"])
        check_row("deployment upgrade")
        rollback()
        upgraded = False
        ready_pod(pod["uid"])
        check_row("deployment rollback")
        verify_clients()
    finally:
        # Restore the starting revision if verification failed after the upgrade.
        if upgraded:
            rollback()
            ready_pod()
        sql(f"DROP TABLE IF EXISTS {table};")
    print("PASS: synthetic table removed; database deployment restored", flush=True)


if __name__ == "__main__":
    main()
