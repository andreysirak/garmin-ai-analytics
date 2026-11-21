package dev.garmin.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

public record EvaluationSuite(String version, String rubricVersion, JsonNode protocol, List<JsonNode> cases) {
    public static final List<String> EXPLANATION_CHECKS = List.of(
            "factual_accuracy", "coverage", "relevance", "no_unsupported_claims");

    public static EvaluationSuite load() throws IOException {
        return from(readFixture("cases.json"));
    }

    static JsonNode readFixture(String name) throws IOException {
        try (var input = EvaluationSuite.class.getResourceAsStream("/dataset/v1/" + name)) {
            if (input == null) {
                throw new IOException("Missing bundled evaluation fixture " + name);
            }
            return Json.MAPPER.readTree(input);
        }
    }

    static EvaluationSuite from(JsonNode document) {
        var ordered = document.required("cases");
        if (!ordered.isArray() || ordered.isEmpty()) {
            throw new IllegalArgumentException("Expected a nonempty array of evaluation cases");
        }
        var protocol = document.required("protocol");
        LocalDate.parse(Json.text(protocol, "reference_date"));
        ZoneId.of(Json.text(protocol, "timezone"));
        for (String key : List.of("context_tokens", "response_tokens", "max_query_days")) {
            var number = protocol.path(key);
            if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 1) {
                throw new IllegalArgumentException("Invalid protocol value for " + key);
            }
        }
        var range = protocol.required("evidence_range");
        var start = LocalDate.parse(Json.text(range, "startDate"));
        var end = LocalDate.parse(Json.text(range, "endDate"));
        if (ChronoUnit.DAYS.between(start, end) != 6) {
            throw new IllegalArgumentException("Evidence range must cover exactly seven days");
        }

        var ids = new HashSet<String>();
        for (var scenario : ordered) {
            String id = Json.text(scenario, "id");
            if (!ids.add(id) || !id.matches("[A-Z0-9-]+")) {
                throw new IllegalArgumentException("Invalid or duplicate case ID");
            }
            Json.text(scenario, "question");
            if (!Set.of("regression", "holdout").contains(Json.text(scenario, "split"))) {
                throw new IllegalArgumentException("Unknown case split");
            }
            switch (Json.text(scenario, "stage")) {
                case "routing" -> Json.text(scenario.required("expected"), "route");
                case "explanation" -> {
                    EvidenceOracle.evidence(scenario, protocol);
                    var checks = scenario.required("review_checks");
                    if (!checks.isArray() || checks.size() != EXPLANATION_CHECKS.size()
                            || !new HashSet<>(strings(checks)).equals(Set.copyOf(EXPLANATION_CHECKS))) {
                        throw new IllegalArgumentException("Invalid explanation rubric");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown case stage");
            }
        }
        return new EvaluationSuite(Json.text(document, "version"), Json.text(document, "rubric_version"),
                protocol, StreamSupport.stream(ordered.spliterator(), false).toList());
    }

    public List<JsonNode> select(String split, String stage) {
        var selected = cases.stream()
                .filter(c -> split.equals("all") || split.equals(c.get("split").asText()))
                .filter(c -> stage.equals("all") || stage.equals(c.get("stage").asText()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No cases selected");
        }
        return selected;
    }

    public static List<String> strings(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText).toList();
    }
}
