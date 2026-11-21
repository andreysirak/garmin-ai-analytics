package dev.garmin.evaluation.evaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.model.ModelProvider.Completion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResponseEvaluatorTest {
    private static final String SELECTION = """
            {"route":"ANALYZE","selection":{"metrics":["totalSteps"],"aggregation":"SUM",
             "period":{"type":"LAST_N_DAYS","days":7}}}
            """;

    private final EvaluationSuite suite = EvaluationSuite.load();
    private final RoutingEvaluator routing = new RoutingEvaluator(suite.protocol());

    ResponseEvaluatorTest() throws Exception {}

    @Test
    void allRoutingExpectationsHaveAnAcceptedContractShape() {
        for (var scenario : suite.select("all", "routing")) {
            var expected = scenario.get("expected");
            var output = (ObjectNode) expected.deepCopy();
            boolean clarification = output.get("route").asText().equals("CLARIFY");
            if (clarification) {
                var target = output.get("target");
                output.set("target", target.isArray() ? target.get(0) : target);
                output.put("question", "Could you clarify?");
            }
            assertEquals(clarification ? Status.NEEDS_REVIEW : Status.PASS,
                    routing.grade(expected, Json.encode(output)).status(), scenario.get("id").asText());
        }
    }

    @Test
    void distinguishesValidWrongRouteMetricCalculationPeriodReasonAndTarget() {
        assertEquals(Status.PASS, routing.grade(expected("ROUTE-006"), SELECTION).status());
        var wrongPeriod = routing.grade(expected("ROUTE-001"), SELECTION);
        assertEquals(List.of("wrong_period"), wrongPeriod.failures());
        assertEquals("2026-11-12", wrongPeriod.details().at("/routing_validation/range/startDate").asText());
        assertEquals("2026-11-18", wrongPeriod.details().at("/routing_validation/range/endDate").asText());
        assertEquals(List.of("wrong_route"), routing.grade(expected("ROUTE-002"), SELECTION).failures());
        assertEquals(List.of("wrong_metric_or_calculation"),
                routing.grade(expected("ROUTE-006"), SELECTION.replace("SUM", "AVG")).failures());
        assertEquals(List.of("wrong_reason"), routing.grade(expected("LIMIT-001"),
                "{\"route\":\"UNSUPPORTED\",\"reason\":\"OTHER\"}").failures());
        assertEquals(List.of("wrong_clarification_target"), routing.grade(expected("ROUTE-002"),
                "{\"route\":\"CLARIFY\",\"target\":\"PERIOD\",\"question\":\"When?\"}").failures());
        assertEquals(Status.NEEDS_REVIEW, routing.grade(expected("ROUTE-005"),
                "{\"route\":\"CLARIFY\",\"target\":\"METRIC\",\"question\":\"Which metric?\"}").status());
        assertEquals("2026-11-09", routing.grade(expected("ROUTE-001"),
                SELECTION.replace("\"LAST_N_DAYS\",\"days\":7", "\"LAST_WEEK\""))
                .details().at("/routing_validation/range/startDate").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"route\":\"ANALYZE\",\"route\":\"UNSUPPORTED\"}",
            "{\"route\":\"UNSUPPORTED\",\"reason\":\"RELATIONSHIP\"}{}",
            "{\"route\":\"CLARIFY\",\"target\":\"GOAL\",\"question\":\"\"}",
            "{\"route\":\"CLARIFY\",\"target\":\"GOAL\",\"question\":1}",
            "{\"route\":\"UNSUPPORTED\",\"reason\":\"RELATIONSHIP\",\"question\":\"Why?\"}",
            "not json"
    })
    void rejectsMalformedOrInconsistentRouting(String response) {
        assertEquals(List.of("routing_contract_rejected"), routing.grade(expected("ROUTE-006"), response).failures());
    }

    @Test
    void rejectsUnknownMetricsUnsupportedOperationsAndInvalidDates() {
        var invalid = List.of(
                SELECTION.replace("totalSteps", "bodyWeight"), SELECTION.replace("totalSteps", "restingHeartRate"),
                SELECTION.replace("\"totalSteps\"", "\"totalSteps\",\"totalSteps\""),
                SELECTION.replace("days\":7", "days\":7.0"), SELECTION.replace("days\":7", "days\":\"7\""),
                SELECTION.replace("days\":7", "days\":32"), SELECTION.replace("days\":7", "days\":0"),
                SELECTION.replace("\"LAST_N_DAYS\",\"days\":7", "\"EXPLICIT\",\"startDate\":\"2026-11-20\",\"endDate\":\"2026-11-21\""),
                SELECTION.replace("\"LAST_N_DAYS\",\"days\":7", "\"EXPLICIT\",\"startDate\":\"2026-02-30\",\"endDate\":\"2026-03-01\""));
        invalid.forEach(output -> assertEquals(List.of("routing_contract_rejected"),
                routing.grade(expected("ROUTE-006"), output).failures()));
    }

    @Test
    void matchingNumbersCannotApproveFalseExplanationOrApplicationAnswer() {
        var scenario = suite.select("regression", "explanation").getFirst();
        var verdict = ResponseEvaluator.model(scenario, suite.protocol(), routing,
                new Completion("There were 3000 steps every day.", true, false));
        assertEquals(Status.NEEDS_REVIEW, verdict.status());
        assertEquals("3000", verdict.details().at("/numeric_hints/0").asText());
        assertEquals(4, verdict.reviewRequired().size());
        assertEquals(Status.FAIL, ResponseEvaluator.model(scenario, suite.protocol(), routing,
                new Completion("3000", false, false)).status());
        assertEquals(Status.FAIL, ResponseEvaluator.model(scenario, suite.protocol(), routing,
                new Completion("3000", true, true)).status());
        assertEquals(Status.FAIL, ResponseEvaluator.model(scenario, suite.protocol(), routing,
                new Completion(" ", true, false)).status());
        assertEquals(Status.NEEDS_REVIEW, ResponseEvaluator.application(Json.object().put("answer", "3000 steps")).status());
        assertEquals(Status.FAIL, ResponseEvaluator.application(Json.object().put("answer", 3000)).status());
        assertEquals(Status.FAIL, ResponseEvaluator.application(Json.object()).status());
    }

    private JsonNode expected(String id) {
        return suite.cases().stream().filter(c -> c.get("id").asText().equals(id))
                .findFirst().orElseThrow().get("expected");
    }
}
