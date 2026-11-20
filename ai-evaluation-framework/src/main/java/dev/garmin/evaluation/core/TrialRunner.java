package dev.garmin.evaluation.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;

public final class TrialRunner {
    @FunctionalInterface
    public interface Exchange {
        JsonNode send(JsonNode input) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface Grader {
        Verdict grade(JsonNode response) throws IOException;
    }

    private TrialRunner() {}

    public static ObjectNode execute(JsonNode scenario, String stage, int trial, JsonNode input,
                                     Exchange exchange, Grader grader) throws InterruptedException {
        var row = Json.object().put("case_id", scenario.required("id").asText())
                .put("stage", stage).put("trial", trial)
                .put("case_sha256", Json.hash(scenario)).put("input_sha256", Json.hash(input))
                .put("started_at", Instant.now().toString());
        row.set("input", input);
        row.putArray("failures");
        row.putArray("review_required");
        long started = System.nanoTime();
        try {
            var response = exchange.send(input);
            row.put("elapsed_seconds", elapsed(started));
            row.set("response", response);
            row.put("output_sha256", Json.hash(response));
            grader.grade(response).writeTo(row);
        } catch (IOException error) {
            row.put("status", Status.ERROR.name()).put("error_type", error.getClass().getSimpleName());
            if (!row.has("elapsed_seconds")) {
                row.put("elapsed_seconds", elapsed(started));
            }
        }
        return row;
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }
}
