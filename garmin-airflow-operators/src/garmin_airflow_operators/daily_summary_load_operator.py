"""Load Garmin daily summaries through the shared JSON archive loader."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from airflow.exceptions import AirflowException

from garmin_airflow_operators.json_archive_load_operator import JsonArchiveLoadOperator


def _calendar_date(value: object, field: str) -> date:
    try:
        parsed = date.fromisoformat(value) if isinstance(value, str) else None
        if parsed is None or parsed.isoformat() != value:
            raise ValueError
    except ValueError:
        raise AirflowException(f"{field} must be a valid YYYY-MM-DD date") from None
    return parsed


class GarminDailySummaryLoadOperator(JsonArchiveLoadOperator):
    """Validate the daily-summary business key and bind the full original JSON."""

    archive_root = Path("/data/raw/garmin/daily-summary")
    sql_resource = "sql/daily_summary_upsert.sql"

    def prepare_parameters(self, payload: dict, raw_json: str) -> tuple[int, date, str]:
        expected_date = _calendar_date(self.processing_date, "Resolved processing date")
        profile_id = payload.get("userProfileId")
        if type(profile_id) is not int or not 0 < profile_id <= 9223372036854775807:
            raise AirflowException("Daily-summary userProfileId must be a positive bigint integer")
        calendar_date = _calendar_date(payload.get("calendarDate"), "Daily-summary calendarDate")
        if calendar_date != expected_date:
            raise AirflowException(
                f"Daily-summary calendarDate {calendar_date} "
                f"does not match resolved processing date {expected_date}"
            )
        return profile_id, calendar_date, raw_json
