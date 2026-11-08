"""KubernetesPodOperator for the standalone Garmin sleep job."""

from __future__ import annotations

import os
from datetime import timedelta
from urllib.parse import urlsplit

from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from kubernetes.client import models as k8s

DEFAULT_IMAGE = "garmin-sleep:local"
DEFAULT_API_BASE_URL = "https://personal.connectapi.garmin.com"
DEFAULT_OUTPUT_DIR = "/data/raw/garmin"
TOKEN_SECRET = "garmin-access-token"
TOKEN_SECRET_KEY = "access-token"
TOKEN_MOUNT_PATH = "/var/run/garmin-token"
RAW_DATA_CLAIM = "garmin-raw-data"


def _raw_data_group_id() -> int:
    value = os.getenv("GARMIN_RAW_DATA_GID", "10001")
    if not value.isdecimal():
        raise ValueError("GARMIN_RAW_DATA_GID must be a non-negative integer")
    return int(value)


class SleepIngestionOperator(KubernetesPodOperator):
    """Runs the standalone sleep JAR in an isolated Kubernetes Pod."""

    def __init__(
        self,
        *,
        date: str,
        api_base_url: str = DEFAULT_API_BASE_URL,
        output_dir: str = DEFAULT_OUTPUT_DIR,
        image: str = DEFAULT_IMAGE,
        **kwargs: object,
    ) -> None:
        parsed_url = urlsplit(api_base_url)
        if (
            parsed_url.scheme not in {"https", "http"}
            or not parsed_url.hostname
            or parsed_url.username is not None
            or parsed_url.password is not None
            or parsed_url.query
            or parsed_url.fragment
        ):
            raise ValueError("api_base_url must be an HTTP(S) URL")
        if not output_dir.startswith("/"):
            raise ValueError("output_dir must be an absolute container path")

        super().__init__(
            cmds=["java", "-jar", "/app/garmin-sleep.jar"],
            arguments=[
                "--date",
                date,
                "--api-base-url",
                api_base_url,
                "--output-dir",
                output_dir,
            ],
            namespace="airflow-jobs",
            service_account_name="airflow-job",
            image=image,
            image_pull_policy="IfNotPresent",
            env_vars=[
                k8s.V1EnvVar(
                    name="GARMIN_TOKEN_FILE",
                    value=f"{TOKEN_MOUNT_PATH}/{TOKEN_SECRET_KEY}",
                )
            ],
            volumes=[
                k8s.V1Volume(
                    name="garmin-access-token",
                    secret=k8s.V1SecretVolumeSource(
                        secret_name=TOKEN_SECRET,
                        default_mode=0o440,
                        items=[
                            k8s.V1KeyToPath(
                                key=TOKEN_SECRET_KEY,
                                path=TOKEN_SECRET_KEY,
                            )
                        ],
                    ),
                ),
                k8s.V1Volume(
                    name="garmin-raw-data",
                    persistent_volume_claim=k8s.V1PersistentVolumeClaimVolumeSource(
                        claim_name=RAW_DATA_CLAIM
                    ),
                ),
            ],
            volume_mounts=[
                k8s.V1VolumeMount(
                    name="garmin-access-token",
                    mount_path=TOKEN_MOUNT_PATH,
                    read_only=True,
                ),
                k8s.V1VolumeMount(
                    name="garmin-raw-data",
                    mount_path=output_dir,
                    read_only=False,
                ),
            ],
            container_resources=k8s.V1ResourceRequirements(
                requests={"cpu": "100m", "memory": "256Mi"},
                limits={"cpu": "1", "memory": "512Mi"},
            ),
            security_context=k8s.V1PodSecurityContext(
                fs_group=10001,
                fs_group_change_policy="OnRootMismatch",
                supplemental_groups=[_raw_data_group_id()],
            ),
            container_security_context=k8s.V1SecurityContext(
                allow_privilege_escalation=False,
                run_as_non_root=True,
                run_as_user=10001,
                run_as_group=10001,
            ),
            in_cluster=True,
            get_logs=True,
            log_events_on_failure=True,
            on_finish_action="delete_pod",
            startup_timeout_seconds=180,
            execution_timeout=timedelta(minutes=10),
            **kwargs,
        )
