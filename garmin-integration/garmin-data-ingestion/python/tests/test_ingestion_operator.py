from types import SimpleNamespace
from unittest.mock import MagicMock, PropertyMock, patch

import pytest
from airflow.exceptions import AirflowException
from airflow.providers.cncf.kubernetes.hooks.kubernetes import KubernetesHook
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator

from garmin_data_ingestion import GarminIngestionOperator
from garmin_data_ingestion.operator import OUTPUT_DIR_XCOM_KEY


def task_context(*, run_id="manual__one", try_number=1, dag_id="garmin_daily_pipeline"):
    return {
        "ti": SimpleNamespace(
            dag_id=dag_id, run_id=run_id, try_number=try_number, xcom_push=MagicMock()
        )
    }


def ingestion_task(*, output_dir="/data/raw/garmin/synthetic-attempt", **kwargs):
    return GarminIngestionOperator(
        task_id="ingest",
        endpoint="/synthetic/resource",
        date="2026-11-07",
        output_dir=output_dir,
        **kwargs,
    )


def build_pod(operator):
    with patch.object(
        KubernetesHook, "is_in_cluster", new_callable=PropertyMock, return_value=True
    ):
        return operator.build_pod_request_obj(context={})


@pytest.mark.parametrize(
    "query_params",
    [
        {},
        {"empty": "", "unicode": "søvn 東京", "spaces": " a b ", "equals": "a=b=="},
    ],
)
def test_generic_query_values_are_separate_unencoded_arguments(query_params):
    operator = ingestion_task(query_params=query_params)
    context = task_context()
    with patch.object(KubernetesPodOperator, "execute_sync"):
        operator.execute(context)
    pod = build_pod(operator)
    container = pod.spec.containers[0]
    output_dir = context["ti"].xcom_push.call_args.kwargs["value"]
    assert container.command == ["java", "-jar", "/app/garmin-data-ingestion.jar"]
    assert container.args[:6] == [
        "--endpoint",
        "/synthetic/resource",
        "--date",
        "2026-11-07",
        "--output-dir",
        output_dir,
    ]
    assert container.args[6:] == [
        arg for key, value in query_params.items() for arg in ("--query-param", f"{key}={value}")
    ]
    assert "--api-base-url" not in container.args
    assert not any(c.name == "airflow-xcom-sidecar" for c in pod.spec.containers)


def test_templates_render_before_building_request_arguments():
    operator = GarminIngestionOperator(
        task_id="render",
        endpoint="{{ params.endpoint }}",
        date="{{ ds }}",
        output_dir="/data/raw/garmin/{{ ds }}/attempt-{{ ti.try_number }}",
        query_params={"calendarDate": "{{ ds }}", "optional": "{{ params.empty }}"},
    )
    context = task_context()
    context.update(ds="2026-11-08", params={"endpoint": "/synthetic/daily", "empty": ""})
    operator.render_template_fields(context)
    with patch.object(KubernetesPodOperator, "execute_sync"):
        operator.execute(context)
    assert operator.arguments[:4] == ["--endpoint", "/synthetic/daily", "--date", "2026-11-08"]
    assert operator.output_dir == "/data/raw/garmin/2026-11-08/attempt-1"
    assert operator.arguments[5] == operator.output_dir
    assert operator.arguments[6:] == [
        "--query-param",
        "calendarDate=2026-11-08",
        "--query-param",
        "optional=",
    ]


def test_pvc_mount_and_secret_security_are_preserved(monkeypatch):
    monkeypatch.setenv("GARMIN_RAW_DATA_GID", "20")
    operator = ingestion_task(
        archive_root="/archive",
        output_dir="/archive/caller-selected-directory",
        image="garmin-sleep:local",
    )
    with patch.object(KubernetesPodOperator, "execute_sync"):
        operator.execute(task_context())
    pod = build_pod(operator)
    container = pod.spec.containers[0]
    assert pod.metadata.namespace == "airflow-jobs"
    assert pod.spec.service_account_name == "airflow-job"
    assert container.image == "garmin-sleep:local"
    assert container.image_pull_policy == "IfNotPresent"
    env = next(e for e in container.env if e.name == "GARMIN_TOKEN_FILE")
    assert env.value == "/var/run/garmin-token/token"
    token = next(v for v in pod.spec.volumes if v.name == "garmin-access-token").secret
    assert token.secret_name == "garmin-access-token"
    assert token.default_mode == 0o440
    assert [(i.key, i.path) for i in token.items] == [("access-token", "token")]
    mounts = {m.name: m for m in container.volume_mounts}
    assert mounts["garmin-access-token"].mount_path == "/var/run/garmin-token"
    assert mounts["garmin-access-token"].read_only is True
    assert mounts["garmin-raw-data"].mount_path == "/archive"
    assert mounts["garmin-raw-data"].read_only is False
    assert operator.arguments[5] == "/archive/caller-selected-directory"
    raw_volume = next(v for v in pod.spec.volumes if v.name == "garmin-raw-data")
    assert raw_volume.persistent_volume_claim.claim_name == "garmin-raw-data"
    assert pod.spec.security_context.supplemental_groups == [20]
    assert pod.spec.security_context.fs_group == 10001
    assert pod.spec.security_context.fs_group_change_policy == "OnRootMismatch"
    security = container.security_context
    assert security.run_as_non_root is True
    assert security.run_as_user == security.run_as_group == 10001
    assert security.allow_privilege_escalation is False
    assert container.resources.requests == {"cpu": "100m", "memory": "256Mi"}
    assert container.resources.limits == {"cpu": "1", "memory": "512Mi"}
    assert operator.in_cluster and operator.get_logs and operator.log_events_on_failure
    assert operator.on_finish_action.value == "delete_pod"
    assert operator.startup_timeout_seconds == 180
    assert operator.execution_timeout.total_seconds() == 600
    assert operator.do_xcom_push is False
    assert operator.deferrable is False
    assert operator.reattach_on_restart is False


def test_default_group_and_root(monkeypatch):
    monkeypatch.delenv("GARMIN_RAW_DATA_GID", raising=False)
    operator = ingestion_task()
    assert operator.security_context.supplemental_groups == [10001]
    assert next(m for m in operator.volume_mounts if m.name == "garmin-raw-data").mount_path == (
        "/data/raw/garmin"
    )


@pytest.mark.parametrize("value", ["-1", "group"])
def test_invalid_shared_storage_group_is_rejected(monkeypatch, value):
    monkeypatch.setenv("GARMIN_RAW_DATA_GID", value)
    with pytest.raises(ValueError, match="GARMIN_RAW_DATA_GID"):
        ingestion_task()


def test_requires_absolute_archive_root():
    with pytest.raises(ValueError, match="absolute"):
        ingestion_task(archive_root="relative/path")


def test_requires_absolute_rendered_output_directory():
    operator = ingestion_task(output_dir="{{ params.output_dir }}")
    context = task_context()
    context["params"] = {"output_dir": "relative/path"}
    operator.render_template_fields(context)
    with patch.object(KubernetesPodOperator, "execute_sync") as run_pod:
        with pytest.raises(ValueError, match="output_dir must be an absolute"):
            operator.execute(context)
    run_pod.assert_not_called()
    context["ti"].xcom_push.assert_not_called()


def test_failure_propagates_without_publishing_output_directory():
    context = task_context()
    with patch.object(
        KubernetesPodOperator, "execute_sync", side_effect=AirflowException("fixture")
    ):
        with pytest.raises(AirflowException, match="fixture"):
            ingestion_task().execute(context)
    context["ti"].xcom_push.assert_not_called()


def test_xcom_contains_only_output_directory_after_success():
    context = task_context()
    operator = ingestion_task()
    with patch.object(KubernetesPodOperator, "execute_sync") as run_pod:
        operator.execute(context)
    run_pod.assert_called_once_with(context)
    context["ti"].xcom_push.assert_called_once()
    assert context["ti"].xcom_push.call_args.kwargs["key"] == OUTPUT_DIR_XCOM_KEY
    output_dir = context["ti"].xcom_push.call_args.kwargs["value"]
    assert isinstance(output_dir, str)
    assert output_dir == operator.arguments[5] == "/data/raw/garmin/synthetic-attempt"
