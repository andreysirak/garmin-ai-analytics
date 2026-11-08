from unittest.mock import PropertyMock, patch

import pytest
from airflow.providers.cncf.kubernetes.hooks.kubernetes import KubernetesHook
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator

from garmin_sleep.operator import SleepIngestionOperator


def build_pod(operator: SleepIngestionOperator):
    with patch.object(
        KubernetesHook,
        "is_in_cluster",
        new_callable=PropertyMock,
        return_value=True,
    ):
        return operator.build_pod_request_obj(context={})


def test_sleep_operator_builds_standalone_job_pod(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GARMIN_RAW_DATA_GID", "20")
    operator = SleepIngestionOperator(task_id="sleep", date="2026-11-07")

    pod = build_pod(operator)

    assert isinstance(operator, KubernetesPodOperator)
    assert pod.metadata.namespace == "airflow-jobs"
    assert pod.spec.service_account_name == "airflow-job"
    container = pod.spec.containers[0]
    assert container.command == ["java", "-jar", "/app/garmin-sleep.jar"]
    assert container.args == [
        "--date",
        "2026-11-07",
        "--api-base-url",
        "https://personal.connectapi.garmin.com",
        "--output-dir",
        "/data/raw/garmin",
    ]
    token_volume = next(volume for volume in pod.spec.volumes if volume.name == "garmin-access-token")
    token_mount = next(mount for mount in container.volume_mounts if mount.name == "garmin-access-token")
    assert token_volume.secret.secret_name == "garmin-access-token"
    assert token_mount.read_only is True
    raw_mount = next(mount for mount in container.volume_mounts if mount.name == "garmin-raw-data")
    assert raw_mount.mount_path == "/data/raw/garmin"
    assert raw_mount.read_only is False
    assert container.security_context.run_as_user == 10001
    assert pod.spec.security_context.supplemental_groups == [20]
