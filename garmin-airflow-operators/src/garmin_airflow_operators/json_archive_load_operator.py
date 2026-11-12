"""Shared archive validation and transactional loading for JSON object responses."""

from __future__ import annotations

import json
from importlib import resources
from pathlib import Path

from airflow.exceptions import AirflowException
from airflow.providers.postgres.hooks.postgres import PostgresHook
from airflow.sdk import BaseOperator, Context, XComArg


def _reject_json_constant(value: str) -> None:
    raise ValueError("Non-standard JSON constant")


def read_json_archive(json_file: object) -> tuple[dict, str]:
    """Read a completed archive and return its object and unchanged UTF-8 text."""
    if not isinstance(json_file, str) or not Path(json_file).is_absolute():
        raise AirflowException("json_file must be an absolute file path")
    try:
        file_path = Path(json_file)
        directory = file_path.parent.resolve()
        if not directory.is_dir():
            raise AirflowException("JSON file parent is not an existing archive directory")
        archive = file_path.resolve()
        marker = directory / "_SUCCESS"
        if any(not path.resolve().is_relative_to(directory) for path in (archive, marker)):
            raise AirflowException("JSON archive and marker must stay within the source directory")
        if not marker.is_file() or marker.stat().st_size != 0:
            raise AirflowException(f"JSON archive lacks an empty _SUCCESS marker: {directory}")
        raw_json = archive.read_bytes().decode("utf-8")
    except UnicodeError:
        raise AirflowException("Archive is not valid UTF-8 JSON") from None
    except OSError as error:
        raise AirflowException(f"Cannot read JSON archive: {error.strerror}") from None
    except (RuntimeError, ValueError):
        raise AirflowException("Cannot resolve JSON archive directory or files") from None
    try:
        payload = json.loads(raw_json, parse_constant=_reject_json_constant)
    except ValueError:
        raise AirflowException("Archive is not valid JSON") from None
    if not isinstance(payload, dict):
        raise AirflowException("Archive JSON must be an object")
    return payload, raw_json


class JsonArchiveLoadOperator(BaseOperator):
    """Load a completed source archive using subclass validation and packaged SQL.

    Callers supply a SQL resource and subclasses implement ``prepare_parameters``.
    SQL paths are relative to ``sql_package``.
    """

    template_fields = ("json_file", "processing_date")
    sql_package = "garmin_airflow_operators"

    def __init__(
        self,
        *,
        json_file: str | XComArg,
        processing_date: str,
        postgres_conn_id: str,
        sql_resource: str,
        **kwargs: object,
    ) -> None:
        self.json_file = json_file
        self.processing_date = processing_date
        self.postgres_conn_id = postgres_conn_id
        self.sql_resource = sql_resource
        kwargs["do_xcom_push"] = False
        super().__init__(**kwargs)

    def prepare_parameters(self, payload: dict, raw_json: str) -> tuple:
        """Validate payload-specific fields and return SQL bind parameters."""
        raise NotImplementedError("Archive loader subclasses must implement prepare_parameters")

    def execute(self, context: Context) -> None:
        payload, raw_json = read_json_archive(self.json_file)
        parameters = self.prepare_parameters(payload, raw_json)
        try:
            sql = resources.files(self.sql_package).joinpath(self.sql_resource).read_text(encoding="utf-8")
        except (OSError, UnicodeError, ModuleNotFoundError):
            raise AirflowException(f"Cannot read packaged SQL resource: {self.sql_resource}") from None
        PostgresHook(postgres_conn_id=self.postgres_conn_id, log_sql=False).run(
            sql=sql,
            parameters=parameters,
            autocommit=False,
        )
