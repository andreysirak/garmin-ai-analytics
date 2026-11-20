package dev.garmin.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.review.HumanReview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportsTest {
    @Test
    void reportsAddedMissingInconclusiveAndDefinitiveTransitions() {
        var baseline = List.<JsonNode>of(row("A", "PASS"), row("B", "FAIL"), row("C", "ERROR"),
                row("D", "PASS"), row("F", "SKIP"));
        var candidate = List.<JsonNode>of(row("A", "FAIL"), row("B", "PASS"), row("C", "PASS"),
                row("E", "PASS"), row("F", "NEEDS_REVIEW"));
        var manifest = Json.object().put("protocol_sha256", "protocol").put("prompt_sha256", "old");
        var result = Reports.compare(manifest, baseline, manifest.deepCopy().put("prompt_sha256", "new"), candidate);
        assertEquals("routing/A/1", result.at("/regressions/0").asText());
        assertEquals("routing/B/1", result.at("/improvements/0").asText());
        assertEquals(2, result.get("inconclusive").size());
        assertEquals("routing/D/1", result.at("/missing/0").asText());
        assertEquals("routing/E/1", result.at("/added/0").asText());
        assertEquals("prompt_sha256", result.at("/factors_changed/0").asText());
        assertFalse(Reports.cleanComparison(result));
        assertThrows(IllegalArgumentException.class, () -> Reports.compare(manifest, baseline,
                manifest.deepCopy().put("protocol_sha256", "different"), candidate));
        assertThrows(IllegalArgumentException.class, () -> Capture.index(List.of(row("A", "PASS"), row("A", "FAIL"))));
    }

    @Test
    void excludesErrorsAndSkipsFromTimingQuantiles() {
        var stats = Reports.describe(List.of(row("A", "ERROR").put("elapsed_seconds", 80).put("error_type", "IOException"),
                row("B", "SKIP").put("elapsed_seconds", 20), row("C", "NEEDS_REVIEW").put("elapsed_seconds", 1),
                row("D", "FAIL").put("elapsed_seconds", 3)));
        assertEquals(2.0, stats.at("/routing/median_seconds").asDouble());
        assertEquals(3.0, stats.at("/routing/p95_seconds").asDouble());
        assertEquals(1, stats.at("/routing/error_counts/IOException").asInt());
        assertEquals(1, stats.at("/routing/counts/SKIP").asInt());
    }

    @Test
    void bindsReviewsToBothHashesAndExactRubric() {
        var row = row("A", "NEEDS_REVIEW").put("output_sha256", "output");
        row.putArray("review_required").add("factual_accuracy");
        var annotation = Json.object().put("input_sha256", "input").put("output_sha256", "output")
                .put("reviewer", "Reviewer").put("rationale", "Evidence supports the answer");
        annotation.putObject("checks").put("factual_accuracy", "PASS");
        assertEquals("PASS", HumanReview.applyOne(row, annotation).get("status").asText());
        assertEquals("NEEDS_REVIEW", row.get("status").asText());
        assertEquals("FAIL", HumanReview.applyOne(row.deepCopy().put("status", "FAIL"), annotation).get("status").asText());
        for (String field : List.of("input_sha256", "output_sha256", "reviewer", "rationale")) {
            assertThrows(IllegalArgumentException.class, () -> HumanReview.applyOne(row, annotation.deepCopy().put(field, " ")));
        }
        var unknown = Json.object().set("routing/B/1", annotation);
        assertThrows(IllegalArgumentException.class, () -> HumanReview.apply(List.of(row), unknown));
        assertThrows(IllegalArgumentException.class, () -> HumanReview.applyOne(row, annotation.deepCopy()
                .set("checks", Json.object().put("extra", "PASS"))));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode row(String id, String status) {
        return Json.object().put("case_id", id).put("stage", "routing").put("trial", 1)
                .put("status", status).put("input_sha256", "input");
    }
}
