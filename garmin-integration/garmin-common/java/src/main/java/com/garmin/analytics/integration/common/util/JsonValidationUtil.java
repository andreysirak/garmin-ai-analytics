package com.garmin.analytics.integration.common.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import java.io.IOException;

/**
 * Checks response JSON syntax without mapping fields or modifying the original text.
 *
 * <p>Parser details are omitted from failures because they can contain private response values.
 */
public final class JsonValidationUtil {

  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private JsonValidationUtil() {}

  /**
   * Checks that the response contains exactly one complete, syntactically valid JSON value.
   *
   * <p>Objects, arrays, scalars, and JSON {@code null} are accepted without field-level validation.
   *
   * @param rawJson the non-null original response body
   * @throws InvalidResponseException if the body is empty, malformed, or contains multiple values
   */
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
