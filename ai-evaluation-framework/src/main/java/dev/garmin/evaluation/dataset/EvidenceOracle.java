package dev.garmin.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EvidenceOracle {
    private static final List<String> GAPS = List.of(
            "missingSummaryDays", "missingMetricDays", "nullMetricDays", "invalidTypeDays");

    private EvidenceOracle() {}

    public static ObjectNode evidence(JsonNode scenario, JsonNode protocol) {
        var observations = scenario.required("observations");
        if (!observations.isArray() || observations.size() != 7) {
            throw new IllegalArgumentException("Versioned fixtures cover exactly seven days");
        }
        var coverage = Json.object().put("expectedDays", 7);
        GAPS.forEach(gap -> coverage.put(gap, 0));
        var readings = new ArrayList<BigDecimal>();
        for (var observation : observations) {
            String kind = Json.text(observation, "kind");
            if ("reading".equals(kind)) {
                readings.add(decimal(observation.required("value")));
            } else if (GAPS.contains(kind)) {
                coverage.put(kind, coverage.get(kind).intValue() + 1);
            } else {
                throw new IllegalArgumentException("Unknown synthetic observation category");
            }
        }
        String operation = Json.text(scenario, "aggregation");
        if (!List.of("SUM", "AVG", "MIN", "MAX").contains(operation)) {
            throw new IllegalArgumentException("Unsupported fixture operation");
        }
        var divisor = decimal(scenario.path("divisor").isMissingNode()
                ? Json.MAPPER.valueToTree(1) : scenario.get("divisor"));
        if (divisor.signum() <= 0) {
            throw new IllegalArgumentException("Fixture divisor must be positive");
        }
        var expected = scenario.required("expected_value");
        var metric = Json.object().put("metric", Json.text(scenario, "metric"));
        if (readings.isEmpty() && expected.isNull()) {
            metric.putNull("value");
        } else if (!readings.isEmpty() && !expected.isNull()) {
            var value = decimal(expected);
            // Verify equality by multiplying back, avoiding rounding or repeating decimals.
            var scaled = value.multiply(divisor);
            var total = readings.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean matches = switch (operation) {
                case "SUM" -> scaled.compareTo(total) == 0;
                case "AVG" -> scaled.multiply(BigDecimal.valueOf(readings.size())).compareTo(total) == 0;
                case "MIN" -> scaled.compareTo(readings.stream().min(Comparator.naturalOrder()).orElseThrow()) == 0;
                case "MAX" -> scaled.compareTo(readings.stream().max(Comparator.naturalOrder()).orElseThrow()) == 0;
                default -> throw new IllegalStateException("Unknown calculation");
            };
            if (!matches) {
                throw new IllegalArgumentException("Expected value disagrees with independent oracle");
            }
            metric.put("value", value);
        } else {
            throw new IllegalArgumentException("Expected value disagrees with observation availability");
        }
        int missingSummaries = coverage.get("missingSummaryDays").intValue();
        coverage.put("daysWithData", readings.size())
                .put("zeroDays", readings.stream().filter(value -> value.signum() == 0).count())
                .put("summaryDays", 7 - missingSummaries)
                .put("missingDays", missingSummaries + coverage.get("missingMetricDays").intValue()
                        + coverage.get("nullMetricDays").intValue())
                .put("complete", readings.size() == 7);
        metric.put("unit", Json.text(scenario, "unit")).set("coverage", coverage);
        var evidence = Json.object().put("aggregation", operation);
        evidence.set("dateRange", protocol.required("evidence_range"));
        evidence.putArray("metrics").add(metric);
        return evidence;
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            return new BigDecimal(value.asText());
        }
        throw new IllegalArgumentException("Expected finite decimal");
    }
}
