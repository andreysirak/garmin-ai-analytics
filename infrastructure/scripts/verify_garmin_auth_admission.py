"""Verify Garmin auth namespace RBAC and admission using server-side dry runs."""

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


def kubectl(*arguments: str, input_data: dict[str, Any] | None = None) -> str:
    command = ["kubectl", "--context", CONTEXT, *arguments]
    result = subprocess.run(
        command,
        input=json.dumps(input_data) if input_data is not None else None,
        capture_output=True,
        text=True,
        check=False,
    )
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
    actual = kubectl(
        "auth",
        "can-i",
        verb,
        resource,
        "--namespace",
        namespace,
        "--as",
        service_account,
    )
    if actual != expected:
        raise RuntimeError(
            f"Unexpected authorization for {service_account}: "
            f"{verb} {resource} in {namespace} is {actual}, expected {expected}"
        )


def auth_pod() -> dict[str, Any]:
    return {
        "apiVersion": "v1",
        "kind": "Pod",
        "metadata": {
            "name": "garmin-auth-policy-check",
            "namespace": "garmin-auth",
        },
        "spec": {
            "serviceAccountName": "garmin-auth",
            "restartPolicy": "Never",
            "securityContext": {
                "runAsNonRoot": True,
                "runAsUser": 10001,
                "runAsGroup": 10001,
                "seccompProfile": {"type": "RuntimeDefault"},
            },
            "containers": [
                {
                    "name": "base",
                    "image": "garmin-auth:local",
                    "imagePullPolicy": "IfNotPresent",
                    "command": ["garmin-auth"],
                    "args": [],
                    "env": [
                        {
                            "name": "GARMIN_API_KEY",
                            "valueFrom": {
                                "secretKeyRef": {
                                    "name": "garmin-api-credentials",
                                    "key": "api-key",
                                }
                            },
                        },
                        {
                            "name": "GARMIN_TOKEN_SECRET_NAMESPACE",
                            "value": "airflow-jobs",
                        },
                    ],
                    "envFrom": [
                        {"configMapRef": {"name": "garmin-auth-config"}}
                    ],
                    "resources": {
                        "requests": {"cpu": "50m", "memory": "96Mi"},
                        "limits": {"cpu": "250m", "memory": "192Mi"},
                    },
                    "securityContext": {
                        "allowPrivilegeEscalation": False,
                        "runAsNonRoot": True,
                        "runAsUser": 10001,
                        "runAsGroup": 10001,
                        "readOnlyRootFilesystem": True,
                        "capabilities": {"drop": ["ALL"]},
                    },
                    "ports": [],
                    "volumeMounts": [],
                }
            ],
            "initContainers": [],
            "volumes": [],
            "imagePullSecrets": [],
            "hostNetwork": False,
        },
    }


def expect_admission(pod: dict[str, Any], admitted: bool, label: str) -> None:
    command = [
        "kubectl",
        "--context",
        CONTEXT,
        "--as",
        AUTH_SERVICE_ACCOUNT,
        "create",
        "--dry-run=server",
        "-f",
        "-",
    ]
    result = subprocess.run(
        command,
        input=json.dumps(pod),
        capture_output=True,
        text=True,
        check=False,
    )
    if (result.returncode == 0) != admitted:
        expectation = "admitted" if admitted else "rejected"
        detail = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"Expected {label} Pod to be {expectation}: {detail}")


def main() -> int:
    try:
        can_i("create", "pods", "garmin-auth", AUTH_SERVICE_ACCOUNT, "yes")
        can_i("get", "secrets", "garmin-auth", AUTH_SERVICE_ACCOUNT, "no")
        can_i("get", "secret/garmin-api-credentials", "garmin-auth", AUTH_SERVICE_ACCOUNT, "no")
        can_i(
            "get",
            "secret/garmin-api-credentials",
            "garmin-auth",
            TOKEN_SERVICE_ACCOUNT,
            "no",
        )
        can_i("get", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "yes")
        can_i("update", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "yes")
        can_i("create", "secrets", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no")
        can_i("list", "secrets", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no")
        can_i("delete", "secret/garmin-access-token", "airflow-jobs", TOKEN_SERVICE_ACCOUNT, "no")
        can_i("update", "secret/garmin-access-token", "airflow-jobs", JAVA_SERVICE_ACCOUNT, "no")

        valid = auth_pod()
        expect_admission(valid, True, "approved")

        unauthorized_variants = {
            "arbitrary image": lambda pod: pod["spec"]["containers"][0].update(
                image="attacker:latest"
            ),
            "arbitrary command": lambda pod: pod["spec"]["containers"][0].update(
                command=["sh", "-c", "id"]
            ),
            "sidecar": lambda pod: pod["spec"]["containers"].append(
                {"name": "sidecar", "image": "busybox"}
            ),
            "volume mount": lambda pod: pod["spec"]["containers"][0].update(
                volumeMounts=[{"name": "credentials", "mountPath": "/credentials"}]
            ),
            "different ServiceAccount": lambda pod: pod["spec"].update(
                serviceAccountName="default"
            ),
            "privileged container": lambda pod: pod["spec"]["containers"][0].update(
                securityContext={
                    "privileged": True,
                    "allowPrivilegeEscalation": True,
                }
            ),
            "lifecycle hook": lambda pod: pod["spec"]["containers"][0].update(
                lifecycle={"postStart": {"exec": {"command": ["sh", "-c", "id"]}}}
            ),
            "host process namespace": lambda pod: pod["spec"].update(hostPID=True),
            "other Secret reference": lambda pod: pod["spec"]["containers"][0]["env"][
                0
            ]["valueFrom"]["secretKeyRef"].update(name="other-secret"),
        }
        for label, mutate in unauthorized_variants.items():
            candidate = copy.deepcopy(valid)
            mutate(candidate)
            candidate["metadata"]["name"] = "garmin-auth-policy-negative"
            expect_admission(candidate, False, label)

    except (RuntimeError, OSError) as error:
        print(f"Garmin auth security verification failed: {error}", file=sys.stderr)
        return 1

    print("Garmin auth RBAC and admission checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
