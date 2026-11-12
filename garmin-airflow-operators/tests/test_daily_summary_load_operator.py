"""Daily-summary validation, packaged UPSERT, and mocked database calls."""

import json
import tomllib
from datetime import date
from importlib import resources
from pathlib import Path
from unittest.mock import patch

import pytest
from airflow.exceptions import AirflowException

from garmin_airflow_operators import GarminDailySummaryLoadOperator, JsonArchiveLoadOperator
from garmin_airflow_operators import json_archive_load_operator as loader_module


@pytest.mark.parametrize("field, value, error", [
    *[("userProfileId", value, "userProfileId") for value in
      [None, True, False, 0, -1, 1.5, 12.0, "12", [], {}, 9223372036854775808]],
    *[("calendarDate", value, "valid YYYY-MM-DD") for value in
      [None, True, 20261109, "", "2026-02-30", "20261109", "2026-11-09T00:00:00"]],
    ("calendarDate", "2026-11-08", "does not match resolved processing date"),
])
def test_subclass_rejects_bad_keys(task, payload, field, value, error):
    task.processing_date = "2026-11-09"
    with pytest.raises(AirflowException, match=error):
        task.prepare_parameters({**payload, field: value}, json.dumps(payload))


@pytest.mark.parametrize("field", ["userProfileId", "calendarDate"])
def test_subclass_rejects_missing_key(task, payload, field):
    task.processing_date = "2026-11-09"
    payload.pop(field)
    with pytest.raises(AirflowException, match=field):
        task.prepare_parameters(payload, json.dumps(payload))


@pytest.mark.parametrize("processing_date", [None, "2026-02-30", "20261109"])
def test_subclass_rejects_invalid_processing_date(task, payload, processing_date):
    task.processing_date = processing_date
    with pytest.raises(AirflowException, match="Resolved processing date"):
        task.prepare_parameters(payload, json.dumps(payload))


@pytest.mark.parametrize("profile_id", [1, 9223372036854775807])
def test_subclass_accepts_bigint_limits_and_preserves_decimal_text(task, payload, profile_id):
    task.processing_date = "2026-11-09"
    payload["userProfileId"] = profile_id
    precise = "0.12345678901234567890123456789"
    raw_json = json.dumps(payload)[:-1] + ', "precise": ' + precise + "}\r\n"
    assert task.prepare_parameters(payload, raw_json) == (profile_id, date(2026, 11, 9), raw_json)


@pytest.mark.parametrize("json_filename", ["2026-11-09.json", "custom-response.json"])
def test_daily_summary_loads_dag_filename_and_packaged_upsert(
    task, archive, context, caplog, json_filename
):
    assert isinstance(task, JsonArchiveLoadOperator)
    before = archive.read_bytes()
    archive.rename(archive.parent / json_filename)
    task.json_file = str(archive.parent / json_filename)
    # Neither a newer attempt nor loader try 99 should replace the published path.
    other = archive.parent.parent / "5"
    other.mkdir()
    (other / json_filename).write_text("invalid JSON")
    task.render_template_fields(context)
    with patch.object(loader_module, "PostgresHook") as hook:
        assert task.execute(context) is None
    context["ti"].xcom_pull.assert_not_called()
    hook.assert_called_once_with(postgres_conn_id="synthetic_loader", log_sql=False)
    hook.return_value.run.assert_called_once()
    call = hook.return_value.run.call_args.kwargs
    assert call["sql"] == resources.files("garmin_airflow_operators").joinpath(
        "sql/daily_summary_upsert.sql"
    ).read_text(encoding="utf-8")
    assert "INSERT INTO staging.stg_garmin_daily_summary" in call["sql"]
    assert "(user_profile_id, calendar_date, payload)" in call["sql"]
    assert "ON CONFLICT (user_profile_id, calendar_date)" in call["sql"]
    assert "DO UPDATE SET payload = EXCLUDED.payload" in call["sql"]
    assert call["sql"].count("%s") == 3
    assert call["parameters"] == (12, date(2026, 11, 9), before.decode("utf-8"))
    assert call["autocommit"] is False
    assert "sensitive synthetic value" not in call["sql"]
    assert "sensitive synthetic value" not in caplog.text
    assert task.do_xcom_push is False
    context["ti"].xcom_push.assert_not_called()
    assert (archive.parent / json_filename).read_bytes() == before


def test_both_fields_are_templated(task, context, archive):
    task.render_template_fields(context)
    assert task.template_fields == ("json_file", "processing_date")
    assert task.processing_date == "2026-11-09"
    assert task.json_file == str(archive)


@pytest.mark.parametrize("failure", ["missing_path", "missing_file", "marker", "json", "date", "profile"])
def test_validation_failure_precedes_sql_loading_and_database(task, archive, context, tmp_path, failure):
    if failure == "missing_path":
        task.json_file = None
    elif failure == "missing_file":
        context["params"]["json_file"] = str(tmp_path / "outside.json")
    elif failure == "marker":
        (archive.parent / "_SUCCESS").unlink()
    elif failure == "json":
        archive.write_bytes(b'{"userProfileId":')
    elif failure == "date":
        archive.write_text('{"userProfileId":12,"calendarDate":"2026-11-08"}')
    else:
        archive.write_text('{"userProfileId":false,"calendarDate":"2026-11-09"}')
    before = archive.read_bytes()
    task.render_template_fields(context)
    with patch.object(loader_module, "PostgresHook") as hook, \
         patch.object(loader_module.resources, "files") as sql, \
         pytest.raises(AirflowException):
        task.execute(context)
    sql.assert_not_called()
    hook.assert_not_called()
    context["ti"].xcom_push.assert_not_called()
    assert archive.read_bytes() == before


def test_database_failure_propagates_without_changing_archive(task, archive, context):
    before = archive.read_bytes()
    task.render_template_fields(context)
    with patch.object(loader_module, "PostgresHook") as hook:
        hook.return_value.run.side_effect = RuntimeError("synthetic database failure")
        with pytest.raises(RuntimeError, match="synthetic database failure"):
            task.execute(context)
    assert archive.read_bytes() == before
    context["ti"].xcom_push.assert_not_called()


def test_sql_is_declared_as_package_data(task):
    project = Path(__file__).resolve().parents[1]
    config = tomllib.loads((project / "pyproject.toml").read_text())
    assert config["tool"]["setuptools"]["packages"]["find"]["where"] == ["src"]
    assert "sql/*.sql" in config["tool"]["setuptools"]["package-data"]["garmin_airflow_operators"]
    assert resources.files("garmin_airflow_operators").joinpath(
        task.sql_resource
    ).is_file()


def test_subclass_passes_sql_resource_through_its_constructor(task):
    assert task.sql_resource == "sql/daily_summary_upsert.sql"
    assert "sql_resource" not in GarminDailySummaryLoadOperator.__dict__
    assert not hasattr(task, "archive_root")
    assert not hasattr(task, "schema")
    assert not hasattr(task, "table")
