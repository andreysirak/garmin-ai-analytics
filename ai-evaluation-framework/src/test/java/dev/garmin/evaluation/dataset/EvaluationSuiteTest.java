package dev.garmin.evaluation.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationSuiteTest {
    @Test
    void preservesAllIdsAndSplits() throws Exception {
        var suite = EvaluationSuite.load();
        var expected = Stream.of(ids("ROUTE", 6), ids("LIMIT", 2), ids("HOLDOUT", 3), ids("ANSWER", 12))
                .flatMap(stream -> stream).toList();
        assertEquals(expected, suite.cases().stream().map(c -> c.get("id").asText()).toList());
        assertEquals(20, suite.select("regression", "all").size());
        assertEquals(8, suite.select("regression", "routing").size());
        assertEquals(12, suite.select("regression", "explanation").size());
        assertEquals(3, suite.select("holdout", "all").size());
        assertThrows(IllegalArgumentException.class, () -> suite.select("holdout", "explanation"));
    }

    @Test
    void validatesExpectedValuesAndDistinguishesZeroFromMissing() throws Exception {
        var suite = EvaluationSuite.load();
        var cases = suite.select("regression", "explanation");
        var totals = List.of("3000", "null", "0", "1000", "21000", "66", "null", "null", "null", "1000", "5.25", "5.25");
        for (int i = 0; i < totals.size(); i++) {
            assertEquals(totals.get(i), metric(cases.get(i), suite).get("value").asText());
        }
        var average = metric(cases.get(3), suite).get("coverage");
        assertEquals(3, average.get("daysWithData").asInt());
        assertEquals(1, average.get("zeroDays").asInt());
        for (String field : List.of("missingSummaryDays", "missingMetricDays", "nullMetricDays", "invalidTypeDays")) {
            assertEquals(1, average.get(field).asInt());
        }
        assertEquals(3, average.get("missingDays").asInt());
        assertTrue(metric(cases.get(2), suite).at("/coverage/complete").asBoolean());
        assertTrue(metric(cases.get(1), suite).get("value").isNull());
        var wrong = (ObjectNode) cases.getFirst().deepCopy();
        wrong.put("expected_value", new BigDecimal("3000.001"));
        assertThrows(IllegalArgumentException.class, () -> metric(wrong, suite));
        wrong.put("expected_value", 3000);
        ((ObjectNode) wrong.at("/observations/0")).put("value", false);
        assertThrows(IllegalArgumentException.class, () -> metric(wrong, suite));
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        var document = EvaluationSuite.readFixture("cases.json");
        ((ObjectNode) document.at("/cases/1")).put("id", document.at("/cases/0/id").asText());
        assertThrows(IllegalArgumentException.class, () -> EvaluationSuite.from(document));
    }

    @Test
    void includesVersionedSchemaAndSyntheticEvidenceInModelPrompts() throws Exception {
        var suite = EvaluationSuite.load();
        var fixtures = PromptFixtures.load();
        var route = fixtures.forCase(suite.cases().getFirst(), suite.protocol());
        assertEquals(3, route.schema().path("anyOf").size());
        assertTrue(route.messages().getFirst().content().contains("Reference date: 2026-11-19 (Europe/Oslo)"));
        var answer = fixtures.forCase(suite.select("regression", "explanation").getFirst(), suite.protocol());
        assertNull(answer.schema());
        assertEquals("system", answer.messages().getLast().role());
        assertTrue(answer.messages().getLast().content().contains("\"daysWithData\":3"));
    }

    private static Stream<String> ids(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + "-%03d".formatted(i));
    }

    private static JsonNode metric(JsonNode scenario, EvaluationSuite suite) {
        return EvidenceOracle.evidence(scenario, suite.protocol()).at("/metrics/0");
    }
}
