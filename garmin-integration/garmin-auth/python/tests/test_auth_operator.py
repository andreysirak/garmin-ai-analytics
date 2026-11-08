from unittest.mock import PropertyMock, patch

from airflow.providers.cncf.kubernetes.hooks.kubernetes import KubernetesHook
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator

from garmin_auth.operator import GarminAuthOperator


def build_pod(operator: GarminAuthOperator):
    with patch.object(
        KubernetesHook,
        "is_in_cluster",
        new_callable=PropertyMock,
        return_value=True,
    ):
        return operator.build_pod_request_obj(context={})


def test_auth_operator_builds_restricted_token_manager_pod() -> None:
    operator = GarminAuthOperator(task_id="ensure_garmin_token")

    pod = build_pod(operator)

    assert isinstance(operator, KubernetesPodOperator)
    assert pod.metadata.namespace == "garmin-auth"
    assert pod.spec.service_account_name == "garmin-auth"
    container = pod.spec.containers[0]
    assert container.image == "garmin-auth:local"
    assert container.command == ["garmin-auth"]
    assert container.env_from[0].config_map_ref.name == "garmin-auth-config"
    api_key = next(env for env in container.env if env.name == "GARMIN_API_KEY")
    assert api_key.value_from.secret_key_ref.name == "garmin-api-credentials"
    assert api_key.value_from.secret_key_ref.key == "api-key"
    token_namespace = next(
        env for env in container.env if env.name == "GARMIN_TOKEN_SECRET_NAMESPACE"
    )
    assert token_namespace.value == "airflow-jobs"
    assert operator.do_xcom_push is False
    assert container.security_context.run_as_user == 10001
    assert container.security_context.read_only_root_filesystem is True
    assert container.security_context.allow_privilege_escalation is False
    assert pod.spec.security_context.seccomp_profile.type == "RuntimeDefault"


def test_auth_operator_allows_runtime_resource_and_identity_overrides() -> None:
    operator = GarminAuthOperator(
        task_id="auth_custom",
        image="registry.example/garmin-auth:1",
        namespace="garmin-jobs",
        service_account_name="custom-auth",
        credentials_secret="custom-credentials",
        config_map="custom-config",
    )

    pod = build_pod(operator)

    assert pod.metadata.namespace == "garmin-jobs"
    assert pod.spec.service_account_name == "custom-auth"
    container = pod.spec.containers[0]
    assert container.image == "registry.example/garmin-auth:1"
    assert container.env_from[0].config_map_ref.name == "custom-config"
    api_key = next(env for env in container.env if env.name == "GARMIN_API_KEY")
    assert api_key.value_from.secret_key_ref.name == "custom-credentials"
