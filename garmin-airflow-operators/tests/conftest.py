import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from garmin_airflow_operators import GarminDailySummaryLoadOperator


@pytest.fixture
def payload():
    return {
        "userProfileId": 12,
        "calendarDate": "2026-11-09",
        "totalSteps": 8100,
        "bodyBatteryDynamicFeedbackEvent": {"bodyBatteryLevel": "HIGH"},
        "endOfDayBodyBatteryDynamicFeedbackEvent": {"bodyBatteryLevel": "LOW"},
        "bodyBatteryActivityEventList": [{"eventType": "ACTIVITY", "bodyBatteryImpact": -8}],
        "unknownGarminField": {"nested": [1, None, "sensitive synthetic value"]},
        "lastSyncTimestampGMT": "2026-11-09T17:18:29.941Z",
    }


@pytest.fixture
def archive(tmp_path, payload):
    directory = tmp_path / "2026-11-09/4"
    directory.mkdir(parents=True)
    (directory / "_SUCCESS").touch()
    path = directory / "2026-11-09.json"
    path.write_bytes((json.dumps(payload, indent=2) + "\r\n").encode("utf-8"))
    return path


@pytest.fixture
def task(archive):
    return GarminDailySummaryLoadOperator(
        task_id="load_test",
        processing_date="{{ ds }}", json_file="{{ params.json_file }}",
        postgres_conn_id="synthetic_loader",
        do_xcom_push=True,
    )


@pytest.fixture
def context(archive):
    return {
        "ti": SimpleNamespace(
            try_number=99, xcom_pull=MagicMock(side_effect=AssertionError("Loader must not pull XCom")),
            xcom_push=MagicMock(),
        ),
        "ds": "2026-11-09",
        "params": {"json_file": str(archive)},
    }
