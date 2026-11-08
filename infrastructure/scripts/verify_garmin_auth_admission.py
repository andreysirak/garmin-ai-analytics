"""Verify Garmin auth namespace RBAC and Pod admission against the live cluster."""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
from typing import Any

CONTEXT = os.environ.get("KUBE_CONTEXT", "kind-garmin-ai-analytics")
AUTH_SERVICE_ACCOUNT = "system:serviceaccount:airflow:airflow-scheduler"
TOKEN_SERVICE_ACCOUNT = "system:serviceaccount:garmin-auth:garmin-auth"
JAVA_SERVICE_ACCOUNT = "system:serviceaccount:airflow-jobs:airflow-job"
POLICY_DENIAL = (
    "ValidatingAdmissionPolicy 'garmin-auth-pod-restrictions' "
    "with binding 'garmin-auth-pod-restrictions' denied request:"
)
RBAC_CHECKS = (
    ("create", "pods", "garmin-auth", AUTH_SERVICE_ACCOUNT, "yes"),
    ("patch", "pods", "airflow-jobs", AUTH_SERVICE_ACCOUNT, "yes"),
    ("get", "secrets", "garmin-auth", AUTH_SERVICE_ACCOUNT, "no"),
    ("get", "secret/garmin-api-credentials", "garmin-auth", AUTH_SERVICE_ACCOUNT, "no"),
    ("get", "secret/garmin-api-credentials", "garmin-auth", TOKEN_SERVICE_ACCOUNT, "no"),
    ("get", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "yes"),
    ("update", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "yes"),
    ("create", "secrets", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no"),
    ("list", "secrets", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no"),
    ("delete", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no"),
    ("update", "secret/garmin-access-token", "airflow-jobs", JAVA_SERVICE_ACCOUNT, "no"),
)


def run_kubectl(
    *arguments: str, input_data: dict[str, Any] | None = None
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["kubectl", "--context", CONTEXT, *arguments],
        input=json.dumps(input_data) if input_data is not None else None,
        capture_output=True,
        text=True,
        check=False,
    )


def kubectl(*arguments: str) -> str:
    result = run_kubectl(*arguments)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "kubectl command failed")
    return result.stdout.strip()


def can_i(
    verb: str,
    resource: str,
    namespace: str,
    service_account: str,
    expected: str,
) -> None:
    result = run_kubectl(
        "auth",
        "can-i",
        verb,
        resource,
        "--namespace",
        namespace,
        "--as",
        service_account,
    )
    actual = result.stdout.strip()
    valid_exit = result.returncode == 0 or (actual == "no" and result.returncode == 1)
    if actual != expected or not valid_exit or result.stderr.strip():
        raise RuntimeError(
            f"Unexpected authorization for {service_account}: "
            f"{verb} {resource} in {namespace} returned "
            f"{actual or result.stderr.strip() or 'no result'}, expected {expected}"
        )


def generated_auth_pod() -> dict[str, Any]:
    build_pod = """
import json
from kubernetes.client import ApiClient
from garmin_auth.operator import GarminAuthOperator

pod = GarminAuthOperator(task_id="ensure_garmin_token").build_pod_request_obj(context={})
print("GARMIN_AUTH_POD=" + json.dumps(ApiClient().sanitize_for_serialization(pod)))
"""
    output = kubectl(
        "exec",
        "--namespace",
        "airflow",
        "airflow-scheduler-0",
        "--container",
        "scheduler",
        "--",
        "python",
        "-c",
        build_pod,
    )
    payload = next(
        (line.removeprefix("GARMIN_AUTH_POD=") for line in output.splitlines()
         if line.startswith("GARMIN_AUTH_POD=")),
        None,
    )
    if payload is None:
        raise RuntimeError("Airflow operator did not return a Pod specification")
    try:
        pod = json.loads(payload)
    except json.JSONDecodeError as error:
        raise RuntimeError("Airflow operator returned invalid Pod JSON") from error
    if not isinstance(pod, dict):
        raise RuntimeError("Airflow operator returned an invalid Pod specification")
    return pod


def check_admission(
    pod: dict[str, Any],
    label: str,
    expected_denial: str | None = None,
) -> None:
    result = run_kubectl(
        "--as",
        AUTH_SERVICE_ACCOUNT,
        "create",
        "--dry-run=server",
        "-f",
        "-",
        input_data=pod,
    )
    detail = result.stderr.strip() or result.stdout.strip()
    if expected_denial is None:
        if result.returncode:
            raise RuntimeError(f"Expected {label} Pod to be admitted: {detail}")
    elif result.returncode == 0 or expected_denial not in detail:
        raise RuntimeError(
            f"Expected {label} Pod to be denied for {expected_denial!r}; "
            f"kubectl returned {result.returncode}: {detail or 'no error details'}"
        )


def set_field(pod: dict[str, Any], path: tuple[str | int, ...], value: Any) -> None:
    target: Any = pod
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = value


def main() -> int:
    try:
        for check in RBAC_CHECKS:
            can_i(*check)

        valid = generated_auth_pod()
        check_admission(valid, "GarminAuthOperator")

        policy_cases = (
            ("arbitrary image", ("spec", "containers", 0, "image"), "attacker:latest"),
            ("arbitrary command", ("spec", "containers", 0, "command"), ["sh", "-c", "id"]),
            (
                "sidecar",
                ("spec", "containers"),
                valid["spec"]["containers"] + [{"name": "sidecar", "image": "busybox"}],
            ),
            ("different ServiceAccount", ("spec", "serviceAccountName"), "default"),
            (
                "lifecycle hook",
                ("spec", "containers", 0, "lifecycle"),
                {"postStart": {"exec": {"command": ["sh", "-c", "id"]}}},
            ),
            ("host process namespace", ("spec", "hostPID"), True),
            (
                "other Secret reference",
                ("spec", "containers", 0, "env", 0, "valueFrom", "secretKeyRef", "name"),
                "other-secret",
            ),
        )
        for label, path, value in policy_cases:
            candidate = copy.deepcopy(valid)
            set_field(candidate, path, value)
            check_admission(candidate, label, POLICY_DENIAL)

        candidate = copy.deepcopy(valid)
        set_field(candidate, ("spec", "containers", 0, "securityContext", "privileged"), True)
        set_field(
            candidate,
            ("spec", "containers", 0, "securityContext", "allowPrivilegeEscalation"),
            True,
        )
        check_admission(candidate, "privileged container", POLICY_DENIAL)

        candidate = copy.deepcopy(valid)
        set_field(
            candidate,
            ("spec", "containers", 0, "volumeMounts"),
            [{"name": "credentials", "mountPath": "/credentials"}],
        )
        set_field(candidate, ("spec", "volumes"), [{"name": "credentials", "emptyDir": {}}])
        check_admission(candidate, "extra volume and mount", POLICY_DENIAL)

        limit_range_cases = (
            (
                "CPU request exceeds LimitRange maximum",
                (
                    (("spec", "containers", 0, "resources", "requests", "cpu"), "300m"),
                    (("spec", "containers", 0, "resources", "limits", "cpu"), "300m"),
                ),
                "maximum cpu usage per Container",
            ),
            (
                "memory request falls below LimitRange minimum",
                ((("spec", "containers", 0, "resources", "requests", "memory"), "64Mi"),),
                "minimum memory usage per Container",
            ),
            (
                "CPU limit exceeds LimitRange maximum",
                ((("spec", "containers", 0, "resources", "limits", "cpu"), "300m"),),
                "maximum cpu usage per Container",
            ),
            (
                "memory limit exceeds LimitRange maximum",
                ((("spec", "containers", 0, "resources", "limits", "memory"), "256Mi"),),
                "maximum memory usage per Container",
            ),
        )
        for label, changes, reason in limit_range_cases:
            candidate = copy.deepcopy(valid)
            for path, value in changes:
                set_field(candidate, path, value)
            check_admission(candidate, label, reason)

    except (RuntimeError, OSError) as error:
        print(f"Garmin auth security verification failed: {error}", file=sys.stderr)
        return 1

    print("Garmin auth RBAC and admission checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
