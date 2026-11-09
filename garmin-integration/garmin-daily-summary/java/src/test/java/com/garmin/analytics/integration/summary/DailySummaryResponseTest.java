package com.garmin.analytics.integration.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.garmin.analytics.integration.summary.response.DailySummaryResponse;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailySummaryResponseTest {

  @Test
  void mapsFullDocumentedSummaryAndTypedNestedEvents() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    DailySummaryResponse response =
        mapper.readValue(
            """
            {
              "userProfileId": 12,
              "displayName": "synthetic-user",
              "calendarDate": "2026-11-09",
              "totalSteps": 8100,
              "totalDistanceMeters": 6450.5,
              "wellnessStartTimeGmt": "2026-11-09T00:00:00.000Z",
              "lastSyncTimestampGMT": "2026-11-09T15:33:19.567Z",
              "includesWellnessData": true,
              "source": "GARMIN",
              "averageSpo2": 97.5,
              "bodyBatteryDynamicFeedbackEvent": {
                "eventTimestampGmt": "2026-11-09T15:33:19.567Z",
                "bodyBatteryLevel": "HIGH",
                "feedbackShortType": "GOOD",
                "feedbackLongType": "RESTED"
              },
              "endOfDayBodyBatteryDynamicFeedbackEvent": {
                "eventTimestampGmt": "2026-11-09T20:00:00.000Z",
                "bodyBatteryLevel": "LOW"
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
              "isJetLagDay": false,
              "hydrationValueInML": 2100.5,
              "latestRespirationTimeGMT": "2026-11-09T15:33:19.567Z",
              "unknownGarminField": "ignored"
            }
            """,
            DailySummaryResponse.class);

    assertEquals(LocalDate.of(2026, 11, 9), response.calendarDate());
    assertEquals(12L, response.userProfileId());
    assertEquals(8100L, response.totalSteps());
    assertEquals(6450.5, response.totalDistanceMeters());
    assertEquals(Instant.parse("2026-11-09T00:00:00Z"), response.wellnessStartTimeGmt());
    assertEquals(Instant.parse("2026-11-09T15:33:19.567Z"), response.lastSyncTimestampGMT());
    assertEquals(true, response.includesWellnessData());
    assertEquals("GARMIN", response.source());
    assertEquals(97.5, response.averageSpo2());
    assertEquals("HIGH", response.bodyBatteryDynamicFeedbackEvent().bodyBatteryLevel());
    assertEquals("LOW", response.endOfDayBodyBatteryDynamicFeedbackEvent().bodyBatteryLevel());
    assertEquals("ACTIVITY", response.bodyBatteryActivityEventList().get(0).eventType());
    assertEquals(1800000L, response.bodyBatteryActivityEventList().get(0).durationInMilliseconds());
    assertEquals(false, response.isJetLagDay());
    assertEquals(2100.5, response.hydrationValueInML());
    assertEquals(Instant.parse("2026-11-09T15:33:19.567Z"),
        response.latestRespirationTimeGMT());
    assertNull(response.restingHeartRate());
  }

  @Test
  void mapsGmtTimestampWithoutExplicitOffset() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    DailySummaryResponse response = mapper.readValue(
        "{\"calendarDate\":\"2026-11-09\","
            + "\"wellnessStartTimeGmt\":\"2026-11-09T00:00:00.000\"}",
        DailySummaryResponse.class);
    assertEquals(Instant.parse("2026-11-09T00:00:00Z"), response.wellnessStartTimeGmt());
  }

  @Test
  void keepsUnknownAndOptionalFieldsFromBreakingDeserialization() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    DailySummaryResponse response =
        mapper.readValue(
            "{\"calendarDate\":\"2026-11-09\",\"unrecognizedObject\":{\"nested\":true}}",
            DailySummaryResponse.class);
    assertEquals(LocalDate.of(2026, 11, 9), response.calendarDate());
    assertNull(response.bodyBatteryActivityEventList());
  }
}
