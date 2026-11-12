"""Shared identifier validation for archive SQL and Airflow SQL templates."""

import re

from airflow.exceptions import AirflowException


def quote_sql_identifier(value: object) -> str:
    """Quote one PostgreSQL name from the supported ASCII identifier subset.

    Reject qualified names, quotes, literal/dollar delimiters, and names exceeding
    PostgreSQL's 63-byte limit. The same result is safe in DDL and regclass text
    literals inside the staging setup's DO blocks. This is not a value adapter.
    """
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,62}", value):
        raise AirflowException(
            "SQL identifiers must be 1-63 ASCII letters, digits, or underscores, "
            "starting with a letter or underscore"
        )
    return f'"{value}"'
