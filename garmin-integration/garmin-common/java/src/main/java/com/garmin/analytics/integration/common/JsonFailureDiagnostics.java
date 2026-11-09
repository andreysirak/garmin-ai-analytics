package com.garmin.analytics.integration.common;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * Describes JSON mapping failures without printing Garmin API values or raw response content.
 */
public final class JsonFailureDiagnostics {

  private JsonFailureDiagnostics() {}

  public static String describe(JsonProcessingException exception) {
    StringBuilder description = new StringBuilder(exception.getClass().getSimpleName());
    if (exception instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
      description.append(" at ").append(mapping.getPathReference());
    }
    if (exception instanceof MismatchedInputException mismatch
        && mismatch.getTargetType() != null) {
      description.append("; expected ").append(mismatch.getTargetType().getTypeName());
    }
    JsonLocation location = exception.getLocation();
    if (location != null) {
      description.append("; line ").append(location.getLineNr())
          .append(", column ").append(location.getColumnNr());
    }
    return description.toString();
  }
}
