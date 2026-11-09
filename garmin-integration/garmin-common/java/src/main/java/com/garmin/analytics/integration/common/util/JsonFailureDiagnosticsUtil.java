package com.garmin.analytics.integration.common.util;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.time.Instant;
import java.util.regex.Pattern;

/** Describes JSON mapping failures without exposing Garmin API values. */
public final class JsonFailureDiagnosticsUtil {

  private static final Pattern ISO_UTC =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$");
  private static final Pattern ISO_OFFSET =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?[+-]\\d{2}:\\d{2}$");
  private static final Pattern ISO_LOCAL =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?$");
  private static final Pattern NUMERIC_STRING = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");

  private JsonFailureDiagnosticsUtil() {}

  public static String describe(JsonProcessingException exception) {
    StringBuilder description = new StringBuilder(exception.getClass().getSimpleName());
    if (exception instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
      description.append(" at ").append(mapping.getPathReference());
    }
    if (exception instanceof MismatchedInputException mismatch
        && mismatch.getTargetType() != null) {
      description.append("; expected ").append(mismatch.getTargetType().getTypeName());
    }
    if (exception instanceof InvalidFormatException invalid
        && invalid.getTargetType() == Instant.class) {
      description.append("; timestamp format: ").append(timestampFormat(invalid.getValue()));
    }
    JsonLocation location = exception.getLocation();
    if (location != null) {
      description.append("; line ").append(location.getLineNr())
          .append(", column ").append(location.getColumnNr());
    }
    return description.toString();
  }

  private static String timestampFormat(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number) {
      return "number";
    }
    if (!(value instanceof String text)) {
      return "non-string value";
    }
    if (text.isBlank()) {
      return "blank string";
    }
    if (ISO_UTC.matcher(text).matches()) {
      return "ISO-8601 UTC";
    }
    if (ISO_OFFSET.matcher(text).matches()) {
      return "ISO-8601 with offset";
    }
    if (ISO_LOCAL.matcher(text).matches()) {
      return "ISO-8601 without timezone";
    }
    if (NUMERIC_STRING.matcher(text).matches()) {
      return "numeric string";
    }
    return "unrecognized string format";
  }
}
