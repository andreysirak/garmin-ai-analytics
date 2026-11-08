package com.garmin.analytics.integration.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailySummaryResponseTest {

  @Test
  void mapsKnownFieldsIgnoresUnknownFieldsAndAllowsNullableMeasurements() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    DailySummaryResponse response =
        mapper.readValue(
            """
            {
              "calendarDate": "2026-11-07",
              "totalSteps": 8100,
              "unknownGarminField": "ignored"
            }
            """,
            DailySummaryResponse.class);

    assertEquals(LocalDate.of(2026, 11, 7), response.calendarDate());
    assertEquals(8100L, response.totalSteps());
    assertNull(response.restingHeartRate());
  }
}
