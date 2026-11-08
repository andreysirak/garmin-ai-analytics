from unittest.mock import PropertyMock, patch

import pytest
from airflow.providers.cncf.kubernetes.hooks.kubernetes import KubernetesHook
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator

from garmin_daily_summary.operator import DailySummaryIngestionOperator


def build_pod(operator: DailySummaryIngestionOperator):
    with patch.object(
        KubernetesHook,
        "is_in_cluster",
        new_callable=PropertyMock,
        return_value=True,
    ):
        return operator.build_pod_request_obj(context={})


def test_summary_operator_builds_standalone_job_pod() -> None:
    operator = DailySummaryIngestionOperator(
        task_id="summary",
        date="{{ ds }}",
    )

    pod = build_pod(operator)

    assert isinstance(operator, KubernetesPodOperator)
    assert pod.metadata.namespace == "airflow-jobs"
    assert pod.spec.service_account_name == "airflow-job"
    container = pod.spec.containers[0]
    assert container.command == ["java", "-jar", "/app/garmin-daily-summary.jar"]
    assert container.args == [
        "--date",
        "{{ ds }}",
        "--api-base-url",
        "https://personal.connectapi.garmin.com",
        "--output-dir",
        "/data/raw/garmin",
    ]
    assert container.env[0].name == "GARMIN_TOKEN_FILE"
    token_volume = next(volume for volume in pod.spec.volumes if volume.name == "garmin-access-token")
    assert token_volume.secret.secret_name == "garmin-access-token"
    assert token_volume.secret.default_mode == 0o440
    token_mount = next(mount for mount in container.volume_mounts if mount.name == "garmin-access-token")
    assert token_mount.read_only is True
    raw_mount = next(mount for mount in container.volume_mounts if mount.name == "garmin-raw-data")
    assert raw_mount.mount_path == "/data/raw/garmin"
    assert raw_mount.read_only is False
    assert pod.spec.containers[0].security_context.run_as_user == 10001


@pytest.mark.parametrize(
    "api_base_url",
    [
        "file:///tmp/api",
        "https://user:password@example.com",
        "https://example.com/path?token=leaked",
    ],
)
def test_summary_operator_rejects_invalid_or_sensitive_api_urls(api_base_url: str) -> None:
    with pytest.raises(ValueError, match="HTTP"):
        DailySummaryIngestionOperator(
            task_id="invalid",
            date="2026-11-07",
            api_base_url=api_base_url,
        )


def test_summary_operator_requires_absolute_output_path() -> None:
    with pytest.raises(ValueError, match="absolute"):
        DailySummaryIngestionOperator(
            task_id="invalid",
            date="2026-11-07",
            output_dir="relative/path",
        )
