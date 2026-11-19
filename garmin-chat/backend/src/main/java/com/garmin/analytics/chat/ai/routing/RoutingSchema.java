package com.garmin.analytics.chat.ai.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garmin.analytics.chat.ai.routing.RoutingOutput.Route;
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
        schema.remove(List.of("type", "required", "additionalProperties"));
        var alternatives = schema.putArray("anyOf");
        addBranch(alternatives.addObject(), fields, Route.CLARIFY, "target", "question");
        addBranch(alternatives.addObject(), fields, Route.UNSUPPORTED, "reason");
        addBranch(alternatives.addObject(), fields, Route.ANALYZE, "selection");
      }
    }.getJsonSchemaMap();
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
