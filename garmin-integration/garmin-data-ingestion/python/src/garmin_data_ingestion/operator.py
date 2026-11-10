"""Run the shared Garmin JAR, with one immutable archive directory per execution."""

from __future__ import annotations

import os
from collections.abc import Mapping
from datetime import timedelta

from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from airflow.sdk import Context
from kubernetes.client import models as k8s

DEFAULT_IMAGE = "garmin-daily-summary:local"
DEFAULT_ARCHIVE_ROOT = "/data/raw/garmin"
OUTPUT_DIR_XCOM_KEY = "output_dir"
TOKEN_SECRET = "garmin-access-token"
TOKEN_SECRET_KEY = "access-token"
TOKEN_FILE_NAME = "token"
TOKEN_MOUNT_PATH = "/var/run/garmin-token"
RAW_DATA_CLAIM = "garmin-raw-data"


def _raw_data_group_id() -> int:
    value = os.getenv("GARMIN_RAW_DATA_GID", "10001")
    if not value.isdecimal():
        raise ValueError("GARMIN_RAW_DATA_GID must be a non-negative integer")
    return int(value)


class GarminIngestionOperator(KubernetesPodOperator):
    """Fetch an endpoint using explicit, unencoded query parameters.

    ``date`` names the archive file only; include any request date in ``query_params``.
    The DAG supplies a distinct, templated ``output_dir`` for each attempt; the
    operator passes it unchanged to Java. The PVC stays mounted at ``archive_root``.
    Synchronous execution publishes only the output directory after the Pod succeeds,
    without an XCom sidecar. Downstream readers must require the empty ``_SUCCESS`` marker.
    """

    template_fields = (
        *KubernetesPodOperator.template_fields,
        "endpoint",
        "date",
        "query_params",
        "output_dir",
    )

    def __init__(
        self,
        *,
        endpoint: str,
        date: str,
        output_dir: str,
        query_params: Mapping[str, str] | None = None,
        archive_root: str = DEFAULT_ARCHIVE_ROOT,
        image: str = DEFAULT_IMAGE,
        **kwargs: object,
    ) -> None:
        if not archive_root.startswith("/"):
            raise ValueError("archive_root must be an absolute container path")
        self.endpoint = endpoint
        self.date = date
        self.output_dir = output_dir
        self.query_params = dict(query_params or {})
        self.archive_root = archive_root

        super().__init__(
            cmds=["java", "-jar", "/app/garmin-data-ingestion.jar"],
            # Built after Airflow renders the request and output-directory fields.
            arguments=[],
            do_xcom_push=False,
            deferrable=False,
            # A new invocation must launch a Pod using the DAG-supplied directory.
            reattach_on_restart=False,
            namespace="airflow-jobs",
            service_account_name="airflow-job",
            image=image,
            image_pull_policy="IfNotPresent",
            env_vars=[
                k8s.V1EnvVar(
                    name="GARMIN_TOKEN_FILE",
                    value=f"{TOKEN_MOUNT_PATH}/{TOKEN_FILE_NAME}",
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
                                path=TOKEN_FILE_NAME,
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
                    mount_path=archive_root,
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

    def execute(self, context: Context) -> None:
        if not self.output_dir.startswith("/"):
            raise ValueError("output_dir must be an absolute container path")
        self.arguments = [
            "--endpoint",
            self.endpoint,
            "--date",
            self.date,
            "--output-dir",
            self.output_dir,
        ]
        for key, value in self.query_params.items():
            self.arguments.extend(["--query-param", f"{key}={value}"])

        super().execute(context)
        context["ti"].xcom_push(key=OUTPUT_DIR_XCOM_KEY, value=self.output_dir)
