package dev.garmin.evaluation.core;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public record Verdict(Status status, List<String> failures, List<String> reviewRequired, ObjectNode details) {
    public static Verdict pass() {
        return new Verdict(Status.PASS, List.of(), List.of(), Json.object());
    }

    public static Verdict fail(String reason) {
        return new Verdict(Status.FAIL, List.of(reason), List.of(), Json.object());
    }

    public static Verdict review(List<String> checks) {
        return new Verdict(Status.NEEDS_REVIEW, List.of(), checks, Json.object());
    }

    public void writeTo(ObjectNode row) {
        row.setAll(details);
        row.put("status", status.name());
        row.set("failures", Json.MAPPER.valueToTree(failures));
        row.set("review_required", Json.MAPPER.valueToTree(reviewRequired));
    }
}
