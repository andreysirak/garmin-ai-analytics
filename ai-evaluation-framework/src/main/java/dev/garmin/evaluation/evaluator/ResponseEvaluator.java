package dev.garmin.evaluation.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Verdict;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.dataset.EvidenceOracle;
import dev.garmin.evaluation.model.ModelProvider.Completion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ResponseEvaluator {
    private static final List<String> APPLICATION_CHECKS = List.of(
            "routing_behavior", "factual_accuracy", "coverage", "relevance", "no_unsupported_claims");
    private static final Pattern NUMERIC_HINT = Pattern.compile("(?<!\\w)-?\\d+(?:[,.]\\d+)*",
            Pattern.UNICODE_CHARACTER_CLASS);

    private ResponseEvaluator() {}

    public static Verdict application(JsonNode response) {
        var answer = response.path("answer");
        if (!answer.isTextual() || answer.asText().isBlank()) {
            return Verdict.fail("empty_or_invalid_http_answer");
        }
        var verdict = Verdict.review(APPLICATION_CHECKS);
        verdict.details().put("limitation", "Review against the known synthetic deployment and evidence.");
        return verdict;
    }

    public static Verdict model(JsonNode scenario, JsonNode protocol, RoutingEvaluator routing, Completion output) {
        if (!output.complete() || output.toolCalls()) {
            return Verdict.fail("incomplete_or_unexpected_tool_output");
        }
        if (scenario.required("stage").asText().equals("routing")) {
            return output.text().isBlank() ? Verdict.fail("empty_answer")
                    : routing.grade(scenario.required("expected"), output.text());
        }
        var verdict = output.text().isBlank() ? Verdict.fail("empty_answer")
                : Verdict.review(EvaluationSuite.strings(scenario.required("review_checks")));
        var hints = new ArrayList<String>();
        NUMERIC_HINT.matcher(output.text()).results().forEach(match -> hints.add(match.group()));
        verdict.details().set("numeric_hints", Json.MAPPER.valueToTree(hints));
        verdict.details().set("evidence", EvidenceOracle.evidence(scenario, protocol));
        return verdict;
    }
}
