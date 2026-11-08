package com.garmin.analytics.integration.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SleepResponseTest {

  @Test
  void mapsNestedSleepDataIgnoresUnknownFieldsAndAllowsNullableMeasurements() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    SleepResponse response =
        mapper.readValue(
            """
            {
              "dailySleepDTO": {
                "calendarDate": "2026-11-07",
                "sleepTimeSeconds": 27000,
                "unknownGarminField": true
              },
              "unknownTopLevelField": "ignored"
            }
            """,
            SleepResponse.class);

    assertEquals(LocalDate.of(2026, 11, 7), response.dailySleepDTO().calendarDate());
    assertEquals(27000L, response.dailySleepDTO().sleepTimeSeconds());
    assertNull(response.dailySleepDTO().avgHeartRate());
  }
}
