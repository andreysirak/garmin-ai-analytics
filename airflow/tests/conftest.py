from copy import deepcopy
from pathlib import Path

import pytest
from airflow.dag_processing.dagbag import DagBag


@pytest.fixture(scope="module")
def parsed_dag():
    dag_bag = DagBag(dag_folder=str(Path(__file__).parents[1] / "dags"))
    assert not dag_bag.import_errors
    return dag_bag.dags["garmin_daily_pipeline"]


@pytest.fixture
def dag(parsed_dag):
    return deepcopy(parsed_dag)


@pytest.fixture
def summary_payload():
    return {
        "userProfileId": 12,
        "calendarDate": "2026-11-09",
        "totalSteps": 8100,
        "lastSyncTimestampGMT": "2026-11-09T17:18:29.941Z",
        "wellnessStartTimeLocal": "2026-11-09T00:00:00.0",
        "bodyBatteryDynamicFeedbackEvent": {
            "eventTimestampGmt": "2026-11-09T15:33:19.567Z", "bodyBatteryLevel": "HIGH",
        },
        "endOfDayBodyBatteryDynamicFeedbackEvent": {"bodyBatteryLevel": "LOW"},
        "bodyBatteryActivityEventList": [{"eventType": "ACTIVITY", "bodyBatteryImpact": -8}],
        "unknownGarminField": {"nested": [1, None, "preserved"]},
    }
