package dev.garmin.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Reports {
    private Reports() {}

    public static final class Summary {
        private final TreeMap<String, ObjectNode> stages = new TreeMap<>();
        private final TreeMap<String, List<Double>> timings = new TreeMap<>();

        public void accept(JsonNode row) {
            var stage = Json.text(row, "stage");
            var status = Status.valueOf(Json.text(row, "status"));
            var group = stages.computeIfAbsent(stage, key -> {
                var node = Json.object();
                node.putObject("counts");
                return node;
            });
            increment((ObjectNode) group.get("counts"), status.name());
            if (status == Status.ERROR) {
                increment(group.withObjectProperty("error_counts"), Json.text(row, "error_type"));
            }
            if (status != Status.ERROR && status != Status.SKIP && row.has("elapsed_seconds")) {
                timings.computeIfAbsent(stage, key -> new ArrayList<>()).add(row.get("elapsed_seconds").asDouble());
            }
        }

        public ObjectNode json() {
            var output = Json.object();
            stages.forEach((stage, group) -> {
                var stats = group.deepCopy();
                var values = timings.getOrDefault(stage, List.of()).stream().sorted().toList();
                int count = values.size();
                if (count > 0) {
                    double median = (values.get((count - 1) / 2) + values.get(count / 2)) / 2;
                    stats.put("median_seconds", median)
                            .put("p95_seconds", values.get((int) Math.ceil(0.95 * count) - 1));
                }
                output.set(stage, stats);
            });
            return output;
        }
    }

    public static ObjectNode describe(List<JsonNode> rows) {
        var summary = new Summary();
        rows.forEach(summary::accept);
        return summary.json();
    }

    public static String markdown(JsonNode summary) {
        var text = new StringBuilder("""
                # AI evaluation summary

                | Stage | PASS | FAIL | ERROR | SKIP | NEEDS_REVIEW | Median s | p95 s |
                | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
                """);
        summary.properties().forEach(entry -> {
            var group = entry.getValue();
            text.append("| ").append(entry.getKey()).append(" |");
            for (var status : Status.values()) {
                text.append(' ').append(group.path("counts").path(status.name()).asInt()).append(" |");
            }
            text.append(' ').append(seconds(group.path("median_seconds"))).append(" | ")
                    .append(seconds(group.path("p95_seconds"))).append(" |\n");
        });
        return text.append("\nLatency measures HTTP wall time and excludes operational errors and skips.\n").toString();
    }

    public static ObjectNode compare(JsonNode baselineManifest, List<JsonNode> baseline,
                                     JsonNode candidateManifest, List<JsonNode> candidate) {
        if (!Json.text(baselineManifest, "protocol_sha256").equals(Json.text(candidateManifest, "protocol_sha256"))) {
            throw new IllegalArgumentException("Incompatible evaluation protocol. Create a new baseline.");
        }
        var before = Capture.index(baseline);
        var after = Capture.index(candidate);
        var keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        var result = Json.object().put("unchanged", 0);
        for (String field : List.of("regressions", "improvements", "inconclusive", "missing", "added", "changed_inputs")) {
            result.putArray(field);
        }
        for (String key : keys) {
            var first = before.get(key);
            var second = after.get(key);
            if (second == null) {
                add(result, "missing", key);
            } else if (first == null) {
                add(result, "added", key);
            } else {
                if (!first.get("input_sha256").equals(second.get("input_sha256"))) {
                    add(result, "changed_inputs", key);
                }
                var from = Status.valueOf(first.get("status").asText());
                var to = Status.valueOf(second.get("status").asText());
                if (!from.definitive() || !to.definitive()) {
                    add(result, "inconclusive", key);
                } else if (from == to) {
                    increment(result, "unchanged");
                } else {
                    add(result, from == Status.PASS ? "regressions" : "improvements", key);
                }
            }
        }
        result.set("baseline_counts", counts(baseline));
        result.set("candidate_counts", counts(candidate));
        var factors = result.putArray("factors_changed");
        for (String field : List.of("source_sha", "source_dirty", "runner_sha256", "java_version", "prompt_sha256",
                "provider", "model", "settings", "runtime", "timeout_seconds", "deployment_id")) {
            if (!Objects.equals(baselineManifest.get(field), candidateManifest.get(field))) {
                factors.add(field);
            }
        }
        result.put("multiple_recorded_factors_changed", factors.size() > 1);
        result.put("note", "Descriptive trial transitions. No statistical significance is inferred.");
        return result;
    }

    public static boolean cleanComparison(JsonNode result) {
        return List.of("regressions", "missing", "added", "inconclusive").stream()
                .allMatch(key -> result.required(key).isEmpty());
    }

    private static String seconds(JsonNode value) {
        return value.isNumber() ? String.format(Locale.ROOT, "%.3f", value.asDouble()) : "n/a";
    }

    private static ObjectNode counts(List<JsonNode> rows) {
        var output = Json.object();
        rows.forEach(row -> increment(output, Json.text(row, "status")));
        return output;
    }

    private static void increment(ObjectNode output, String key) {
        output.put(key, output.path(key).asInt() + 1);
    }

    private static void add(ObjectNode output, String field, String key) {
        ((ArrayNode) output.get(field)).add(key);
    }
}
