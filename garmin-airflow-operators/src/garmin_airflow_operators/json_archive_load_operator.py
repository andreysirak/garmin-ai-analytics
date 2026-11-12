"""Shared archive validation and transactional loading for JSON object responses."""

from __future__ import annotations

import json
from importlib import resources
from pathlib import Path

from airflow.exceptions import AirflowException
from airflow.providers.postgres.hooks.postgres import PostgresHook
from airflow.sdk import BaseOperator, Context

from garmin_airflow_operators.sql_identifiers import quote_sql_identifier

# Contract with archive-producing tasks, including GarminIngestionOperator.
OUTPUT_DIR_XCOM_KEY = "output_dir"


def _reject_json_constant(value: str) -> None:
    raise ValueError("Non-standard JSON constant")


def read_json_archive(
    output_dir: object, json_filename: str, *, archive_root: Path
) -> tuple[dict, str]:
    """Read a completed archive and return its object and unchanged UTF-8 text."""
    if not isinstance(output_dir, str) or not Path(output_dir).is_absolute():
        raise AirflowException("Source task output_dir XCom must be an absolute directory")
    if not isinstance(json_filename, str) or not json_filename or Path(json_filename).is_absolute():
        raise AirflowException("json_filename must be a nonempty relative path")
    try:
        root = archive_root.resolve()
        directory = Path(output_dir).resolve()
        if not directory.is_relative_to(root):
            raise AirflowException("Source task output_dir must stay within the archive root")
        if not directory.is_dir():
            raise AirflowException("Source task output_dir is not an existing archive directory")
        archive = directory / json_filename
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

    Subclasses define ``archive_root`` and ``sql_resource``, and implement
    ``prepare_parameters``. SQL paths are relative to ``sql_package``.
    """

    template_fields = ("processing_date", "json_filename")
    archive_root: Path | None = None
    sql_package = "garmin_airflow_operators"
    sql_resource: str | None = None

    def __init__(
        self,
        *,
        source_task_id: str,
        processing_date: str,
        json_filename: str,
        postgres_conn_id: str,
        schema: str,
        table: str,
        **kwargs: object,
    ) -> None:
        if self.archive_root is None or self.sql_resource is None:
            raise TypeError("Archive loader subclasses must define archive_root and sql_resource")
        self.source_task_id = source_task_id
        self.processing_date = processing_date
        self.json_filename = json_filename
        self.postgres_conn_id = postgres_conn_id
        quote_sql_identifier(schema)
        quote_sql_identifier(table)
        self.schema = schema
        self.table = table
        kwargs["do_xcom_push"] = False
        super().__init__(**kwargs)

    def prepare_parameters(self, payload: dict, raw_json: str) -> tuple:
        """Validate payload-specific fields and return SQL bind parameters."""
        raise NotImplementedError("Archive loader subclasses must implement prepare_parameters")

    def execute(self, context: Context) -> None:
        output_dir = context["ti"].xcom_pull(
            task_ids=self.source_task_id,
            key=OUTPUT_DIR_XCOM_KEY,
            include_prior_dates=False,
        )
        payload, raw_json = read_json_archive(
            output_dir, self.json_filename, archive_root=self.archive_root
        )
        parameters = self.prepare_parameters(payload, raw_json)
        try:
            sql = resources.files(self.sql_package).joinpath(self.sql_resource).read_text(encoding="utf-8")
        except (OSError, UnicodeError, ModuleNotFoundError):
            raise AirflowException(f"Cannot read packaged SQL resource: {self.sql_resource}") from None
        sql = sql.format(
            schema=quote_sql_identifier(self.schema),
            table=quote_sql_identifier(self.table),
        )
        PostgresHook(postgres_conn_id=self.postgres_conn_id, log_sql=False).run(
            sql=sql,
            parameters=parameters,
            autocommit=False,
        )
