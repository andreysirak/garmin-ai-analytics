package com.garmin.analytics.integration.common.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import java.io.IOException;

/** Checks JSON syntax without mapping, inspecting fields, or changing the response. */
public final class JsonValidationUtil {

  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private JsonValidationUtil() {}

  public static void validate(String rawJson) throws InvalidResponseException {
    try (JsonParser parser = JSON_FACTORY.createParser(rawJson)) {
      if (parser.nextToken() == null) {
        throw new JsonParseException(parser, "Expected a JSON value");
      }
      parser.skipChildren();
      if (parser.nextToken() != null) {
        throw new JsonParseException(parser, "Expected exactly one JSON value");
      }
    } catch (IOException exception) {
      // Jackson exception messages can include private response values.
      throw new InvalidResponseException("Garmin response JSON error");
    }
  }
}
