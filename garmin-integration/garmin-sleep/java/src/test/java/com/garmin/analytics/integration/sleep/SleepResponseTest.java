package com.garmin.analytics.integration.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.garmin.analytics.integration.sleep.response.SleepResponse;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SleepResponseTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void mapsDocumentedSleepResponseWithTypedNestedObjectsAndDynamicScoreKeys()
      throws Exception {
    SleepResponse response =
        mapper.readValue(
            """
            {
              "id": 12,
              "userProfilePK": 42,
              "calendarDate": "2026-11-09",
              "sleepTimeSeconds": 27000,
              "napTimeSeconds": 1200,
              "sleepWindowConfirmed": true,
              "sleepStartTimestampGMT": "2026-11-08T22:00:00.000Z",
              "sleepEndTimestampGMT": "2026-11-09T05:30:00.000Z",
              "sleepStartTimestampLocal": "2026-11-08T22:00:00.000Z",
              "sleepScores": {
                "overall": {"value": 81, "qualifierKey": "GOOD"},
                "customGarminScore": {"value": 72.5, "optimalStart": 70}
              },
              "ageGroup": "TEEN",
              "sleepScoreFeedback": "NONE",
              "sleepNeed": {
                "userProfilePk": 42,
                "calendarDate": "2026-11-09",
                "timestampGmt": "2026-11-09T15:55:23.833Z",
                "baseline": 480,
                "preferredActivityTracker": true
              },
              "nextSleepNeed": {
                "calendarDate": "2026-11-10",
                "recommendedBedtimeStartMins": 1260
              },
              "dailyNapDTOS": [{
                "userProfilePK": 42,
                "calendarDate": "2026-11-09",
                "napTimeSec": 1200,
                "napStartTimestampGMT": "2026-11-09T12:00:00.000Z"
              }],
              "sleepAlignment": {
                "calendarDate": "2026-11-09",
                "isPreferredActivityTracker": true,
                "status": "ON_TIME"
              },
              "unknownGarminField": {"new": true}
            }
            """,
            SleepResponse.class);

    assertEquals(12L, response.id());
    assertEquals(42L, response.userProfilePK());
    assertEquals(LocalDate.of(2026, 11, 9), response.calendarDate());
    assertEquals(27000L, response.sleepTimeSeconds());
    assertEquals(1200L, response.napTimeSeconds());
    assertTrue(response.sleepWindowConfirmed());
    assertEquals(
        Instant.parse("2026-11-08T22:00:00Z"), response.sleepStartTimestampGMT());
    assertEquals("TEEN", response.ageGroup());
    assertEquals(81.0, response.sleepScores().get("overall").value());
    assertEquals("GOOD", response.sleepScores().get("overall").qualifierKey());
    assertEquals(72.5, response.sleepScores().get("customGarminScore").value());
    assertNotNull(response.sleepNeed());
    assertEquals(480, response.sleepNeed().baseline());
    assertTrue(response.sleepNeed().preferredActivityTracker());
    assertEquals(LocalDate.of(2026, 11, 10), response.nextSleepNeed().calendarDate());
    assertEquals(1, response.dailyNapDTOS().size());
    assertEquals(1200L, response.dailyNapDTOS().get(0).napTimeSec());
    assertTrue(response.sleepAlignment().isPreferredActivityTracker());
    assertEquals("ON_TIME", response.sleepAlignment().status());
  }

  @Test
  void allowsAbsentOptionalFieldsAndNoDataPayload() throws Exception {
    SleepResponse response = mapper.readValue("{}", SleepResponse.class);
    assertNull(response.calendarDate());
    assertNull(response.sleepScores());
    assertNull(response.sleepNeed());
    assertNull(response.dailyNapDTOS());
  }
}
