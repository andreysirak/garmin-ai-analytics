package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public record EvaluationSuite(JsonNode document) {
    public static EvaluationSuite load(Path file) throws IOException {
        JsonNode root = JsonSupport.read(file);
        if (!root.path("cases").isArray() || root.path("cases").isEmpty()) {
            throw new IllegalArgumentException("Suite must contain cases");
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode testCase : root.path("cases")) {
            String id = testCase.path("id").asText("");
            String split = testCase.path("split").asText("");
            String stage = testCase.path("stage").asText("");
            if (id.isBlank() || !ids.add(id)) throw new IllegalArgumentException("Duplicate/empty case id: " + id);
            if (!Set.of("regression", "holdout").contains(split)) throw new IllegalArgumentException("Invalid split: " + id);
            if (!Set.of("routing", "explanation").contains(stage)) throw new IllegalArgumentException("Invalid stage: " + id);
            if (testCase.path("question").asText("").isBlank()) throw new IllegalArgumentException("Missing question: " + id);
        }
        return new EvaluationSuite(root);
    }
}