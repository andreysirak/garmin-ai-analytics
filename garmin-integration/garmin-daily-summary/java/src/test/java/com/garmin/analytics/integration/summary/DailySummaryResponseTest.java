package com.garmin.analytics.integration.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.garmin.analytics.integration.summary.response.DailySummaryResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DailySummaryResponseTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void mapsObservedGarminSummaryWithOffsetlessTimestampsAndFractionalValues()
      throws Exception {
    DailySummaryResponse response =
        mapper.readValue(
            """
            {
              "userProfileId": 12,
              "displayName": "synthetic-user",
              "calendarDate": "2026-11-09",
              "totalSteps": 8100,
              "totalDistanceMeters": 6450.5,
              "wellnessStartTimeGmt": "2026-11-08T23:00:00.0",
              "wellnessStartTimeLocal": "2026-11-09T00:00:00.0",
              "wellnessEndTimeGmt": "2026-11-09T17:17:00.0",
              "wellnessEndTimeLocal": "2026-11-09T18:17:00.0",
              "lastSyncTimestampGMT": "2026-11-09T17:18:29.941",
              "latestRespirationTimeGMT": "2026-11-09T17:15:00.0",
              "floorsAscended": 7.66339,
              "floorsDescended": 10.48852,
              "bodyBatteryVersion": 2.0,
              "includesWellnessData": true,
              "source": "GARMIN",
              "averageSpo2": null,
              "isJetLagDay": false,
              "bodyBatteryDynamicFeedbackEvent": {
                "eventTimestampGmt": "2026-11-09T15:33:19.567Z",
                "bodyBatteryLevel": "HIGH",
                "feedbackShortType": "GOOD",
                "feedbackLongType": "RESTED"
              },
              "bodyBatteryActivityEventList": [
                {
                  "eventType": "ACTIVITY",
                  "eventStartTimeGmt": "2026-11-09T08:00:00.000Z",
                  "timezoneOffset": 3600,
                  "durationInMilliseconds": 1800000,
                  "bodyBatteryImpact": -8,
                  "feedbackType": "NORMAL",
                  "shortFeedback": "Workout"
                }
              ],
              "unknownGarminField": "ignored"
            }
            """,
            DailySummaryResponse.class);

    assertEquals(LocalDate.of(2026, 11, 9), response.calendarDate());
    assertEquals(12L, response.userProfileId());
    assertEquals(8100L, response.totalSteps());
    assertEquals(6450.5, response.totalDistanceMeters());
    assertEquals(LocalDateTime.of(2026, 11, 8, 23, 0), response.wellnessStartTimeGmt());
    assertEquals(LocalDateTime.of(2026, 11, 9, 0, 0), response.wellnessStartTimeLocal());
    assertEquals(LocalDateTime.of(2026, 11, 9, 17, 17), response.wellnessEndTimeGmt());
    assertEquals(LocalDateTime.of(2026, 11, 9, 18, 17), response.wellnessEndTimeLocal());
    assertEquals(LocalDateTime.of(2026, 11, 9, 17, 18, 29, 941000000),
        response.lastSyncTimestampGMT());
    assertEquals(LocalDateTime.of(2026, 11, 9, 17, 15), response.latestRespirationTimeGMT());
    assertEquals(7.66339, response.floorsAscended());
    assertEquals(10.48852, response.floorsDescended());
    assertEquals(2.0, response.bodyBatteryVersion());
    assertEquals(true, response.includesWellnessData());
    assertEquals("GARMIN", response.source());
    assertNull(response.averageSpo2());
    assertEquals(false, response.isJetLagDay());
    assertEquals("HIGH", response.bodyBatteryDynamicFeedbackEvent().bodyBatteryLevel());
    assertEquals("ACTIVITY", response.bodyBatteryActivityEventList().get(0).eventType());
    assertEquals(1800000L,
        response.bodyBatteryActivityEventList().get(0).durationInMilliseconds());
    assertNull(response.restingHeartRate());
  }

  @Test
  void mapsOptionalFieldsAndNullsWithoutCustomDeserializer() throws Exception {
    DailySummaryResponse response =
        mapper.readValue(
            """
            {
              "calendarDate": "2026-11-09",
              "wellnessStartTimeGmt": null,
              "wellnessStartTimeLocal": null,
              "lastSyncTimestampGMT": null,
              "latestSpo2ReadingTimeGmt": null,
              "unknownObject": {"nested": true}
            }
            """,
            DailySummaryResponse.class);
    assertEquals(LocalDate.of(2026, 11, 9), response.calendarDate());
    assertNull(response.wellnessStartTimeGmt());
    assertNull(response.wellnessStartTimeLocal());
    assertNull(response.lastSyncTimestampGMT());
    assertNull(response.latestSpo2ReadingTimeGmt());
    assertNull(response.bodyBatteryActivityEventList());
  }
}
