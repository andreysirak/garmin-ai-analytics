package dev.garmin.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;

import java.io.IOException;
import java.util.List;

public record PromptFixtures(JsonNode document) {
    public record Message(String role, String content) {}
    public record Prompt(List<Message> messages, JsonNode schema) {}

    public static PromptFixtures load() throws IOException {
        return new PromptFixtures(EvaluationSuite.readFixture("prompts.json"));
    }

    public Prompt forCase(JsonNode scenario, JsonNode protocol) {
        var user = new Message("user", Json.text(scenario, "question"));
        if (scenario.required("stage").asText().equals("routing")) {
            var system = Json.text(document, "routing_system")
                    .replace("${reference_date}", Json.text(protocol, "reference_date"))
                    .replace("${timezone}", Json.text(protocol, "timezone"))
                    .replace("${max_query_days}", protocol.required("max_query_days").asText());
            return new Prompt(List.of(new Message("system", system), user),
                    document.required("routing_schema"));
        }
        var evidence = EvidenceOracle.evidence(scenario, protocol);
        return new Prompt(List.of(
                new Message("system", Json.text(document, "explanation_system")), user,
                new Message("system", Json.text(document, "evidence_prefix") + Json.encode(evidence))), null);
    }
}
