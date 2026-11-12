"""Safe identifier quoting without connecting to PostgreSQL."""

import pytest
from airflow.exceptions import AirflowException

from garmin_airflow_operators.sql_identifiers import quote_sql_identifier


@pytest.mark.parametrize("value", ["staging", "DailySummary", "select", "_table_1", "a" * 63])
def test_identifiers_are_always_quoted(value):
    assert quote_sql_identifier(value) == '"' + value + '"'


@pytest.mark.parametrize("value", [
    None, 12, True, "", "1table", "schema.table", "with space", 'a"b', "a'b",
    "x; DROP TABLE users", "x--comment", "x$$", "x%", "name\n", "ø", "a" * 64,
])
def test_identifiers_outside_supported_postgresql_subset_are_rejected(value):
    with pytest.raises(AirflowException, match="SQL identifiers must"):
        quote_sql_identifier(value)
