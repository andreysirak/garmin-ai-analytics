package com.garmin.analytics.integration.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonFailureDiagnosticsTest {

  private record Example(Long sleepTimeSeconds) {}

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
