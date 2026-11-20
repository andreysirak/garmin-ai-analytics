package dev.garmin.evaluation.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.dataset.EvaluationSuite;

import java.util.HashSet;
import java.util.List;

public final class HumanReview {
    private HumanReview() {}

    public static String key(JsonNode row) {
        return Json.text(row, "stage") + "/" + Json.text(row, "case_id") + "/" + row.required("trial").intValue();
    }

    public static void addTemplate(ObjectNode template, JsonNode row) {
        if (!row.get("review_required").isEmpty()) {
            var entry = template.putObject(key(row))
                    .put("input_sha256", Json.text(row, "input_sha256"))
                    .put("output_sha256", Json.text(row, "output_sha256"))
                    .put("reviewer", "").put("rationale", "");
            var checks = entry.putObject("checks");
            row.get("review_required").forEach(check -> checks.put(check.asText(), Status.NEEDS_REVIEW.name()));
        }
    }

    public static List<JsonNode> apply(List<JsonNode> rows, JsonNode annotations) {
        var reviewable = new HashSet<String>();
        rows.stream().filter(row -> !row.get("review_required").isEmpty())
                .forEach(row -> reviewable.add(key(row)));
        if (annotations == null || !annotations.isObject() || annotations.properties().stream().anyMatch(entry -> !reviewable.contains(entry.getKey()))) {
            throw new IllegalArgumentException("Annotation references unknown or non-reviewable trial");
        }
        return rows.stream().map(row -> {
            var annotation = annotations.get(key(row));
            return annotation == null ? row : applyOne(row, annotation);
        }).toList();
    }

    public static JsonNode applyOne(JsonNode row, JsonNode annotation) {
        var required = EvaluationSuite.strings(row.required("review_required"));
        if (required.isEmpty() || !Json.text(row, "input_sha256").equals(Json.text(annotation, "input_sha256"))
                || !Json.text(row, "output_sha256").equals(Json.text(annotation, "output_sha256"))) {
            throw new IllegalArgumentException("Review hashes do not match a reviewable input/output");
        }
        Json.text(annotation, "reviewer");
        Json.text(annotation, "rationale");
        var checks = annotation.path("checks");
        if (!checks.isObject() || checks.size() != required.size() || !required.stream().allMatch(checks::has)) {
            throw new IllegalArgumentException("Review must grade exactly the required rubric checks");
        }
        var failures = new java.util.ArrayList<String>();
        boolean pending = false;
        for (String check : required) {
            switch (Json.text(checks, check)) {
                case "PASS" -> { }
                case "FAIL" -> failures.add(check);
                case "NEEDS_REVIEW" -> pending = true;
                default -> throw new IllegalArgumentException("Invalid human review status");
            }
        }
        var result = (ObjectNode) row.deepCopy();
        result.set("review", annotation);
        if (Status.NEEDS_REVIEW.name().equals(row.get("status").asText())) {
            var verdict = !failures.isEmpty() ? Status.FAIL : pending ? Status.NEEDS_REVIEW : Status.PASS;
            result.put("status", verdict.name());
            result.set("failures", Json.MAPPER.valueToTree(failures));
        }
        return result;
    }
}
