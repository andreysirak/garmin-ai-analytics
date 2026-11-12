"""Temporary archive fixtures and mocked generic loading; no database access."""

import json
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException
from airflow.sdk import BaseOperator

from garmin_airflow_operators import JsonArchiveLoadOperator
from garmin_airflow_operators import json_archive_load_operator as loader_module
from garmin_airflow_operators.json_archive_load_operator import read_json_archive


def test_reader_preserves_object_original_text_and_bytes(archive, payload):
    before = archive.read_bytes()
    result = read_json_archive(str(archive))
    assert result == (payload, before.decode("utf-8"))
    assert archive.read_bytes() == before
    assert (archive.parent / "_SUCCESS").read_bytes() == b""


@pytest.mark.parametrize("payload", [{}, {"generic": [1, None, {"nested": True}]}])
def test_generic_reader_has_no_garmin_key_requirements(archive, payload):
    archive.write_text(json.dumps(payload))
    assert read_json_archive(str(archive))[0] == payload


@pytest.mark.parametrize("contents, error", [
    (b'{"unfinished":', "not valid JSON"),
    (b"\xff", "not valid UTF-8 JSON"),
    (b"[]", "must be an object"),
    (b"null", "must be an object"),
    (b'"text"', "must be an object"),
    (b"true", "must be an object"),
    (b"42", "must be an object"),
    (b'{"value": NaN}', "not valid JSON"),
    (b'{"value": Infinity}', "not valid JSON"),
    (b'{"value": -Infinity}', "not valid JSON"),
])
def test_reader_rejects_invalid_json_without_changing_archive(archive, contents, error):
    archive.write_bytes(contents)
    with pytest.raises(AirflowException, match=error):
        read_json_archive(str(archive))
    assert archive.read_bytes() == contents


@pytest.mark.parametrize("json_file", [None, "", "relative/response.json", 42])
def test_reader_requires_absolute_file_path(json_file):
    with pytest.raises(AirflowException, match="json_file must be an absolute file path"):
        read_json_archive(json_file)


@pytest.mark.parametrize("path_kind", ["missing", "file"])
def test_reader_requires_existing_directory(tmp_path, path_kind):
    directory = tmp_path / "attempt"
    if path_kind == "file":
        directory.touch()
    with pytest.raises(AirflowException, match="not an existing archive directory"):
        read_json_archive(str(directory / "response.json"))


@pytest.mark.parametrize("filename", ["_SUCCESS", "2026-11-09.json"])
def test_reader_rejects_file_symlinks_outside_source(archive, tmp_path, filename):
    target = tmp_path / "outside"
    target.write_bytes(b"")
    path = archive.parent / filename
    path.unlink()
    path.symlink_to(target)
    with pytest.raises(AirflowException, match="must stay within the source directory"):
        read_json_archive(str(archive))


@pytest.mark.parametrize("directory_kind", ["normalized", "symlink"])
def test_reader_accepts_normalized_or_symlink_parent(archive, directory_kind):
    directory = archive.parent / "../4"
    if directory_kind == "symlink":
        directory = archive.parent.parent / "alias"
        directory.symlink_to(archive.parent, target_is_directory=True)
    assert read_json_archive(str(directory / archive.name))[0]["userProfileId"] == 12


@pytest.mark.parametrize("filename", ["nested/response.json", "alias.json"])
def test_reader_accepts_contained_relative_filename(archive, filename):
    path = archive.parent / filename
    if filename == "alias.json":
        path.symlink_to(archive)
    else:
        path.parent.mkdir()
        path.write_bytes(archive.read_bytes())
        (path.parent / "_SUCCESS").touch()
    result = read_json_archive(str(path))
    assert result[1] == archive.read_bytes().decode("utf-8")


@pytest.mark.parametrize("missing", ["_SUCCESS", "2026-11-09.json", "nonempty_marker", "marker_directory"])
def test_reader_requires_completed_archive(archive, missing):
    before = archive.read_bytes()
    if missing == "nonempty_marker":
        (archive.parent / "_SUCCESS").write_text("incomplete")
    elif missing == "marker_directory":
        (archive.parent / "_SUCCESS").unlink()
        (archive.parent / "_SUCCESS").mkdir()
    else:
        (archive.parent / missing).unlink()
    error = "Cannot read JSON archive" if missing == archive.name else "_SUCCESS"
    with pytest.raises(AirflowException, match=error):
        read_json_archive(str(archive))
    if archive.exists():
        assert archive.read_bytes() == before


class SyntheticLoadOperator(JsonArchiveLoadOperator):
    sql_package = "synthetic_sql_package"

    def prepare_parameters(self, payload, raw_json):
        return (payload["synthetic"], raw_json)


def test_base_requires_sql_resource_constructor_argument():
    with pytest.raises(TypeError, match="sql_resource"):
        JsonArchiveLoadOperator(
            task_id="unconfigured", processing_date="2026-11-09",
            json_file="/tmp/response.json", postgres_conn_id="test",
        )


def test_generic_operator_delegates_parameters_and_loads_subclass_sql(archive, context):
    class TestLoadOperator(SyntheticLoadOperator):
        pass

    archive.write_text('{"synthetic": "custom value"}\r\n')
    task = TestLoadOperator(
        task_id="custom_load", processing_date="{{ ds }}",
        json_file=str(archive), postgres_conn_id="custom_postgres",
        sql_resource="sql/synthetic.sql",
    )
    assert isinstance(task, BaseOperator)
    task.render_template_fields(context)
    resource = MagicMock()
    resource.joinpath.return_value.read_text.return_value = "SELECT %s, %s::jsonb"
    with patch.object(loader_module.resources, "files", return_value=resource) as files, \
         patch.object(loader_module, "PostgresHook") as hook:
        assert task.execute(context) is None
    files.assert_called_once_with("synthetic_sql_package")
    resource.joinpath.assert_called_once_with("sql/synthetic.sql")
    hook.assert_called_once_with(postgres_conn_id="custom_postgres", log_sql=False)
    hook.return_value.run.assert_called_once_with(
        sql="SELECT %s, %s::jsonb",
        parameters=("custom value", archive.read_bytes().decode("utf-8")), autocommit=False,
    )
    context["ti"].xcom_pull.assert_not_called()
    context["ti"].xcom_push.assert_not_called()


def test_subclass_must_implement_payload_validation(archive, context):
    task = JsonArchiveLoadOperator(
        task_id="incomplete", processing_date="2026-11-09",
        json_file=str(archive), postgres_conn_id="test",
        sql_resource="sql/daily_summary_upsert.sql",
    )
    with patch.object(loader_module, "PostgresHook") as hook, \
         pytest.raises(NotImplementedError, match="prepare_parameters"):
        task.execute(context)
    hook.assert_not_called()


@pytest.mark.parametrize("error", [FileNotFoundError(), UnicodeError(), ModuleNotFoundError()])
def test_missing_or_invalid_sql_fails_before_database(task, context, error):
    task.render_template_fields(context)
    resource = MagicMock()
    resource.joinpath.return_value.read_text.side_effect = error
    with patch.object(loader_module.resources, "files", return_value=resource), \
         patch.object(loader_module, "PostgresHook") as hook, \
         pytest.raises(AirflowException, match="Cannot read packaged SQL resource"):
        task.execute(context)
    hook.assert_not_called()


def test_reader_requires_marker_in_the_files_own_parent(archive):
    nested = archive.parent / "nested" / "response.json"
    nested.parent.mkdir()
    nested.write_bytes(archive.read_bytes())
    # The completed outer directory must not mark the nested directory as complete.
    with pytest.raises(AirflowException, match="_SUCCESS"):
        read_json_archive(str(nested))


def test_reader_rejects_a_directory_in_place_of_the_json_file(archive):
    directory = archive.parent / "not-a-file.json"
    directory.mkdir()
    with pytest.raises(AirflowException, match="Cannot read JSON archive"):
        read_json_archive(str(directory))
