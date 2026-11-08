"""KubernetesPodOperator for ensuring the shared Garmin bearer token."""

from __future__ import annotations

from datetime import timedelta

from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from kubernetes.client import models as k8s


class GarminAuthOperator(KubernetesPodOperator):
    """Runs the standalone Garmin token manager in a restricted Kubernetes Pod."""

    def __init__(
        self,
        *,
        image: str = "garmin-auth:local",
        namespace: str = "garmin-auth",
        service_account_name: str = "garmin-auth",
        credentials_secret: str = "garmin-api-credentials",
        config_map: str = "garmin-auth-config",
        token_secret_namespace: str = "airflow-jobs",
        **kwargs: object,
    ) -> None:
        super().__init__(
            name="ensure-garmin-token",
            namespace=namespace,
            service_account_name=service_account_name,
            image=image,
            image_pull_policy="IfNotPresent",
            cmds=["garmin-auth"],
            env_from=[
                k8s.V1EnvFromSource(
                    config_map_ref=k8s.V1ConfigMapEnvSource(name=config_map)
                )
            ],
            env_vars=[
                k8s.V1EnvVar(
                    name="GARMIN_API_KEY",
                    value_from=k8s.V1EnvVarSource(
                        secret_key_ref=k8s.V1SecretKeySelector(
                            name=credentials_secret,
                            key="api-key",
                        )
                    ),
                ),
                k8s.V1EnvVar(
                    name="GARMIN_TOKEN_SECRET_NAMESPACE",
                    value=token_secret_namespace,
                ),
            ],
            container_resources=k8s.V1ResourceRequirements(
                requests={"cpu": "50m", "memory": "96Mi"},
                limits={"cpu": "250m", "memory": "192Mi"},
            ),
            security_context=k8s.V1PodSecurityContext(
                run_as_non_root=True,
                run_as_user=10001,
                run_as_group=10001,
                seccomp_profile=k8s.V1SeccompProfile(type="RuntimeDefault"),
            ),
            container_security_context=k8s.V1SecurityContext(
                allow_privilege_escalation=False,
                run_as_non_root=True,
                run_as_user=10001,
                run_as_group=10001,
                read_only_root_filesystem=True,
                capabilities=k8s.V1Capabilities(drop=["ALL"]),
            ),
            do_xcom_push=False,
            in_cluster=True,
            get_logs=True,
            log_events_on_failure=True,
            on_finish_action="delete_pod",
            startup_timeout_seconds=180,
            execution_timeout=timedelta(minutes=5),
            **kwargs,
        )
