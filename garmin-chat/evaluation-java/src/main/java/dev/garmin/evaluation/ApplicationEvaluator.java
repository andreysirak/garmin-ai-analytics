package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class ApplicationEvaluator {
    public record Verdict(String status, List<String> failures, List<String> reviewRequired) {}
    private static final List<String> REVIEW = List.of("routing_behavior", "factual_accuracy",
            "coverage", "relevance", "no_unsupported_claims");
    public Verdict evaluate(JsonNode response) {
        JsonNode answer = response == null ? null : response.get("answer");
        if (answer == null || !answer.isTextual() || answer.asText().isBlank()) {
            return new Verdict("FAIL", List.of("empty_or_invalid_http_answer"), List.of());
        }
        return new Verdict("NEEDS_REVIEW", List.of(), REVIEW);
    }
}