package com.garmin.analytics.integration.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonFailureDiagnosticsTest {

  private record Example(Long sleepTimeSeconds) {}

  private record SummaryTimestamps(
      Instant wellnessStartTimeGmt, Instant lastSyncTimestampGMT) {}

  private record SleepTimestamps(Instant sleepStartTimestampGMT) {}

  @Test
  void reportsJacksonExceptionTypeAndFieldWithoutTheRawValue() {
    ObjectMapper mapper = new ObjectMapper();
    try {
      mapper.readValue("{\"sleepTimeSeconds\":\"private-sensitive-value\"}", Example.class);
      throw new AssertionError("Expected Jackson mapping failure");
    } catch (JsonProcessingException exception) {
      String details = JsonFailureDiagnostics.describe(exception);
      assertTrue(details.contains("InvalidFormatException"));
      assertTrue(details.contains("sleepTimeSeconds"));
      assertTrue(details.contains("java.lang.Long"));
      assertFalse(details.contains("private-sensitive-value"));
    }
  }

  @Test
  void reportsTimestampFormatForAnotherSummaryInstantField() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String timestamp = "2026-11-09T18:14:50";
    try {
      mapper.readValue(
          "{\"wellnessStartTimeGmt\":null,\"lastSyncTimestampGMT\":\""
              + timestamp + "\"}",
          SummaryTimestamps.class);
      throw new AssertionError("Expected Jackson mapping failure");
    } catch (JsonProcessingException exception) {
      String details = JsonFailureDiagnostics.describe(exception);
      assertTrue(details.contains("lastSyncTimestampGMT"));
      assertTrue(details.contains("java.time.Instant"));
      assertTrue(details.contains("ISO-8601 without timezone"));
      assertFalse(details.contains(timestamp));
    }
  }

  @Test
  void reportsTimestampFormatForSleepInstantField() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String timestamp = "not-a-real-timestamp";
    try {
      mapper.readValue(
          "{\"sleepStartTimestampGMT\":\"" + timestamp + "\"}",
          SleepTimestamps.class);
      throw new AssertionError("Expected Jackson mapping failure");
    } catch (JsonProcessingException exception) {
      String details = JsonFailureDiagnostics.describe(exception);
      assertTrue(details.contains("sleepStartTimestampGMT"));
      assertTrue(details.contains("unrecognized string format"));
      assertFalse(details.contains(timestamp));
    }
  }

  @Test
  void reportsJsonSyntaxErrorWithoutIncludingRawContent() {
    ObjectMapper mapper = new ObjectMapper();
    try {
      mapper.readTree("{not-json");
      throw new AssertionError("Expected malformed JSON to fail");
    } catch (JsonProcessingException exception) {
      String details = JsonFailureDiagnostics.describe(exception);
      assertTrue(details.contains("JsonParseException"));
      assertTrue(details.contains("line "));
      assertFalse(details.contains("not-json"));
    }
  }
}
