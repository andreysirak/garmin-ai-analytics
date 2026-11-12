"""Temporary archive fixtures and mocked generic loading; no database access."""

import json
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException
from airflow.sdk import BaseOperator

from garmin_airflow_operators import JsonArchiveLoadOperator
from garmin_airflow_operators import json_archive_load_operator as loader_module
from garmin_airflow_operators.json_archive_load_operator import read_json_archive


def test_reader_preserves_object_original_text_and_bytes(archive, archive_root, payload):
    before = archive.read_bytes()
    result = read_json_archive(str(archive.parent), archive.name, archive_root=archive_root)
    assert result == (payload, before.decode("utf-8"))
    assert archive.read_bytes() == before
    assert (archive.parent / "_SUCCESS").read_bytes() == b""


@pytest.mark.parametrize("payload", [{}, {"generic": [1, None, {"nested": True}]}])
def test_generic_reader_has_no_garmin_key_requirements(archive, archive_root, payload):
    archive.write_text(json.dumps(payload))
    assert read_json_archive(str(archive.parent), archive.name, archive_root=archive_root)[0] == payload


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
def test_reader_rejects_invalid_json_without_changing_archive(archive, archive_root, contents, error):
    archive.write_bytes(contents)
    with pytest.raises(AirflowException, match=error):
        read_json_archive(str(archive.parent), archive.name, archive_root=archive_root)
    assert archive.read_bytes() == contents


@pytest.mark.parametrize("output_dir", [None, "", "relative/4", 42])
def test_reader_requires_absolute_directory(archive_root, output_dir):
    with pytest.raises(AirflowException, match="output_dir XCom must be an absolute directory"):
        read_json_archive(output_dir, "response.json", archive_root=archive_root)


@pytest.mark.parametrize("path_kind", ["outside", "sibling_prefix", "traversal", "symlink"])
def test_reader_rejects_directories_resolving_outside_root(archive_root, tmp_path, path_kind):
    outside = tmp_path / "outside"
    outside.mkdir()
    if path_kind == "sibling_prefix":
        directory = tmp_path / "archive-other"
        directory.mkdir()
    elif path_kind == "traversal":
        directory = archive_root / "../outside"
    elif path_kind == "symlink":
        directory = archive_root / "escaped"
        directory.symlink_to(outside, target_is_directory=True)
    else:
        directory = outside
    with pytest.raises(AirflowException, match="must stay within the archive root"):
        read_json_archive(str(directory), "response.json", archive_root=archive_root)


@pytest.mark.parametrize("path_kind", ["missing", "file"])
def test_reader_requires_existing_directory(archive_root, path_kind):
    directory = archive_root / "attempt"
    if path_kind == "file":
        directory.touch()
    with pytest.raises(AirflowException, match="not an existing archive directory"):
        read_json_archive(str(directory), "response.json", archive_root=archive_root)


@pytest.mark.parametrize("filename", [None, "", "/tmp/outside.json"])
def test_reader_requires_relative_filename(archive, archive_root, filename):
    with pytest.raises(AirflowException, match="json_filename must be a nonempty relative path"):
        read_json_archive(str(archive.parent), filename, archive_root=archive_root)


@pytest.mark.parametrize("filename", ["../outside.json", "nested/../../outside.json"])
def test_reader_rejects_filename_traversal(archive, archive_root, filename):
    with pytest.raises(AirflowException, match="must stay within the source directory"):
        read_json_archive(str(archive.parent), filename, archive_root=archive_root)


@pytest.mark.parametrize("filename", ["_SUCCESS", "2026-11-09.json"])
def test_reader_rejects_file_symlinks_outside_source(archive, archive_root, tmp_path, filename):
    target = tmp_path / "outside"
    target.write_bytes(b"")
    path = archive.parent / filename
    path.unlink()
    path.symlink_to(target)
    with pytest.raises(AirflowException, match="must stay within the source directory"):
        read_json_archive(str(archive.parent), archive.name, archive_root=archive_root)


@pytest.mark.parametrize("directory_kind", ["normalized", "symlink"])
def test_reader_accepts_contained_directory(archive, archive_root, directory_kind):
    directory = archive.parent / "../4"
    if directory_kind == "symlink":
        directory = archive_root / "alias"
        directory.symlink_to(archive.parent, target_is_directory=True)
    assert read_json_archive(str(directory), archive.name, archive_root=archive_root)[0]["userProfileId"] == 12


@pytest.mark.parametrize("filename", ["nested/response.json", "alias.json"])
def test_reader_accepts_contained_relative_filename(archive, archive_root, filename):
    path = archive.parent / filename
    if filename == "alias.json":
        path.symlink_to(archive)
    else:
        path.parent.mkdir()
        path.write_bytes(archive.read_bytes())
    result = read_json_archive(str(archive.parent), filename, archive_root=archive_root)
    assert result[1] == archive.read_bytes().decode("utf-8")


@pytest.mark.parametrize("missing", ["_SUCCESS", "2026-11-09.json", "nonempty_marker", "marker_directory"])
def test_reader_requires_completed_archive(archive, archive_root, missing):
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
        read_json_archive(str(archive.parent), archive.name, archive_root=archive_root)
    if archive.exists():
        assert archive.read_bytes() == before


class SyntheticLoadOperator(JsonArchiveLoadOperator):
    sql_package = "synthetic_sql_package"
    sql_resource = "sql/synthetic.sql"

    def prepare_parameters(self, payload, raw_json):
        return (payload["synthetic"], raw_json)


def test_base_requires_subclass_configuration():
    with pytest.raises(TypeError, match="subclasses must define archive_root and sql_resource"):
        JsonArchiveLoadOperator(
            task_id="unconfigured", source_task_id="source", processing_date="2026-11-09",
            json_filename="response.json", postgres_conn_id="test", schema="test_schema", table="test_table",
        )


def test_generic_operator_delegates_parameters_and_loads_subclass_sql(archive, archive_root, context):
    class TestLoadOperator(SyntheticLoadOperator):
        pass

    TestLoadOperator.archive_root = archive_root
    archive.write_text('{"synthetic": "custom value"}\r\n')
    task = TestLoadOperator(
        task_id="custom_load", source_task_id="custom_source", processing_date="{{ ds }}",
        json_filename=archive.name, postgres_conn_id="custom_postgres",
        schema="select", table="MixedCase",
    )
    assert isinstance(task, BaseOperator)
    task.render_template_fields(context)
    resource = MagicMock()
    resource.joinpath.return_value.read_text.return_value = "SELECT %s, %s::jsonb FROM {schema}.{table}"
    with patch.object(loader_module.resources, "files", return_value=resource) as files, \
         patch.object(loader_module, "PostgresHook") as hook:
        assert task.execute(context) is None
    files.assert_called_once_with("synthetic_sql_package")
    resource.joinpath.assert_called_once_with("sql/synthetic.sql")
    hook.assert_called_once_with(postgres_conn_id="custom_postgres", log_sql=False)
    hook.return_value.run.assert_called_once_with(
        sql='SELECT %s, %s::jsonb FROM "select"."MixedCase"',
        parameters=("custom value", archive.read_bytes().decode("utf-8")), autocommit=False,
    )
    context["ti"].xcom_pull.assert_called_once_with(
        task_ids="custom_source", key=loader_module.OUTPUT_DIR_XCOM_KEY, include_prior_dates=False
    )
    context["ti"].xcom_push.assert_not_called()


def test_subclass_must_implement_payload_validation(archive_root, archive, context):
    class IncompleteLoadOperator(JsonArchiveLoadOperator):
        sql_resource = "sql/daily_summary_upsert.sql"

    IncompleteLoadOperator.archive_root = archive_root
    task = IncompleteLoadOperator(
        task_id="incomplete", source_task_id="source", processing_date="2026-11-09",
        json_filename=archive.name, postgres_conn_id="test", schema="test_schema", table="test_table",
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


@pytest.mark.parametrize("field, value", [
    ("schema", 'bad"schema'), ("table", "table; DROP TABLE users"),
    ("schema", "a" * 64), ("table", "schema.table"),
])
def test_constructor_rejects_unsafe_identifiers(field, value):
    from garmin_airflow_operators import GarminDailySummaryLoadOperator

    kwargs = {"schema": "test_schema", "table": "test_table", field: value}
    with patch.object(loader_module, "PostgresHook") as hook, \
         pytest.raises(AirflowException, match="SQL identifiers must"):
        GarminDailySummaryLoadOperator(
            task_id="invalid_identifiers", source_task_id="source",
            processing_date="2026-11-09", json_filename="response.json",
            postgres_conn_id="test", **kwargs,
        )
    hook.assert_not_called()


@pytest.mark.parametrize("field", ["schema", "table"])
def test_identifiers_are_revalidated_before_sql_execution(task, context, field):
    setattr(task, field, 'bad"identifier')
    task.render_template_fields(context)
    with patch.object(loader_module, "PostgresHook") as hook, \
         pytest.raises(AirflowException, match="SQL identifiers must"):
        task.execute(context)
    hook.assert_not_called()
