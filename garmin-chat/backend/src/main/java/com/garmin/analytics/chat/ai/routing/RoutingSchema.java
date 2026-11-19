package com.garmin.analytics.chat.ai.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garmin.analytics.chat.ai.routing.RoutingOutput.Route;
import com.garmin.analytics.chat.model.PeriodSelection.Type;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import java.util.List;
import java.util.Map;
import org.springframework.ai.converter.BeanOutputConverter;

/** The model selects one disjoint shape. Java still validates its response before routing. */
public final class RoutingSchema {

  private RoutingSchema() {}

  public static Map<String, Object> generate() {
    return new BeanOutputConverter<>(RoutingOutput.class) {
      @Override
      protected void postProcessSchema(JsonNode generated) {
        var schema = (ObjectNode) generated;
        var fields = schema.remove("properties");
        constrainSelection((ObjectNode) fields.get("selection"));
        schema.remove(List.of("type", "required", "additionalProperties"));
        var alternatives = schema.putArray("anyOf");
        addBranch(alternatives.addObject(), fields, Route.CLARIFY, "target", "question");
        addBranch(alternatives.addObject(), fields, Route.UNSUPPORTED, "reason");
        addBranch(alternatives.addObject(), fields, Route.ANALYZE, "selection");
      }
    }.getJsonSchemaMap();
  }

  private static void constrainSelection(ObjectNode selection) {
    var properties = (ObjectNode) selection.get("properties");
    var metrics = (ObjectNode) properties.get("metrics");
    metrics.put("minItems", 1);
    metrics.put("maxItems", Metric.values().length);
    metrics.put("uniqueItems", true);
    var names = ((ObjectNode) metrics.get("items")).putArray("enum");
    for (var metric : Metric.values()) {
      names.add(metric.field());
    }
    var periods = properties.putObject("period").putArray("anyOf");
    for (var type : Type.values()) {
      var branch = periods.addObject();
      branch.put("type", "object");
      branch.put("additionalProperties", false);
      var fields = branch.putObject("properties");
      fields.putObject("type").put("const", type.name());
      var required = branch.putArray("required").add("type");
      switch (type) {
        case EXPLICIT -> {
          fields.putObject("startDate").put("type", "string")
              .put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
          fields.putObject("endDate").put("type", "string")
              .put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
          required.add("startDate").add("endDate");
        }
        case LAST_N_DAYS -> {
          fields.putObject("days").put("type", "integer").put("minimum", 1);
          required.add("days");
        }
        default -> { /* Named periods need no generated dates or day count. */ }
      }
    }
  }

  private static void addBranch(ObjectNode branch, JsonNode fields, Route route,
      String... branchFields) {
    branch.put("type", "object");
    branch.put("additionalProperties", false);
    var properties = branch.putObject("properties");
    properties.putObject("route").put("type", "string").putArray("enum").add(route.name());
    var required = branch.putArray("required").add("route");
    for (String field : branchFields) {
      properties.set(field, fields.get(field));
      required.add(field);
    }
  }
}
