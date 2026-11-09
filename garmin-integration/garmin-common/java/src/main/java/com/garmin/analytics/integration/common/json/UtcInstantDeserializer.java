package com.garmin.analytics.integration.common.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Parses ISO-8601 GMT timestamps, including timestamps with no explicit UTC suffix. */
public final class UtcInstantDeserializer extends JsonDeserializer<Instant> {

  @Override
  public Instant deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    if (!parser.currentToken().isScalarValue()) {
      return (Instant) context.handleUnexpectedToken(Instant.class, parser);
    }
    String value = parser.getText();
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      try {
        return OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException ignoredOffset) {
        try {
          return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException invalid) {
          return (Instant) context.handleWeirdStringValue(
              Instant.class, value, "Expected ISO-8601 GMT timestamp");
        }
      }
    }
  }
}
