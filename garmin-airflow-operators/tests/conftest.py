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
def archive_root(tmp_path, monkeypatch):
    root = tmp_path / "archive"
    root.mkdir()
    monkeypatch.setattr(GarminDailySummaryLoadOperator, "archive_root", root)
    return root


@pytest.fixture
def archive(archive_root, payload):
    directory = archive_root / "2026-11-09/4"
    directory.mkdir(parents=True)
    (directory / "_SUCCESS").touch()
    path = directory / "2026-11-09.json"
    path.write_bytes((json.dumps(payload, indent=2) + "\r\n").encode("utf-8"))
    return path


@pytest.fixture
def task():
    return GarminDailySummaryLoadOperator(
        task_id="load_test", source_task_id="synthetic_source",
        processing_date="{{ ds }}", json_filename="{{ ds }}.json",
        postgres_conn_id="synthetic_loader", schema="test_staging", table="test_summary",
        do_xcom_push=True,
    )


@pytest.fixture
def context(archive):
    return {
        "ti": SimpleNamespace(
            try_number=99, xcom_pull=MagicMock(return_value=str(archive.parent)),
            xcom_push=MagicMock(),
        ),
        "ds": "2026-11-09",
    }
