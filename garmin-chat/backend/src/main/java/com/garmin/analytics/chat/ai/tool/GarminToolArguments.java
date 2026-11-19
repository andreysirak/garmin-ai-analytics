package com.garmin.analytics.chat.ai.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** Checks the wire shape before Spring AI converts arguments to Java types. */
@Component
public class GarminToolArguments {

  private final ObjectReader reader;

  public GarminToolArguments(ObjectMapper mapper) {
    this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  boolean valid(String arguments) {
    if (arguments == null) {
      return false;
    }
    try {
      // Spring AI's method callback can ignore unknown fields and coerce inputs.
      JsonNode request = reader.readTree(arguments);
      if (!hasFields(request, "metrics", "aggregation", "period")
          || !request.get("metrics").isArray()
          || !enumName(request.get("aggregation"), Aggregation.values())) {
        return false;
      }
      for (var metric : request.get("metrics")) {
        if (!metric.isTextual()) {
          return false;
        }
      }
      var period = request.get("period");
      return hasFields(period, "type", "startDate", "endDate", "days")
          && enumName(period.get("type"), PeriodSelection.Type.values())
          && period.get("startDate").isTextual() && period.get("endDate").isTextual()
          && period.get("days").isIntegralNumber() && period.get("days").canConvertToInt();
    } catch (JsonProcessingException malformed) {
      return false;
    }
  }

  private static boolean hasFields(JsonNode object, String... fields) {
    return object != null && object.isObject() && object.size() == fields.length
        && Arrays.stream(fields).allMatch(object::has);
  }

  private static boolean enumName(JsonNode node, Enum<?>[] values) {
    return node.isTextual() && Arrays.stream(values).anyMatch(value -> value.name()
        .equals(node.textValue()));
  }
}
