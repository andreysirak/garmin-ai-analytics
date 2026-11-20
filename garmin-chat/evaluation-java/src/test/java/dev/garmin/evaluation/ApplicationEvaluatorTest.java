package dev.garmin.evaluation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationEvaluatorTest {
    private final ApplicationEvaluator evaluator = new ApplicationEvaluator();
    @Test void answerRequiresHumanReviewRatherThanAutomaticPass() throws Exception {
        var response = JsonSupport.mapper().readTree("{\"answer\":\"3000 steps\"}");
        assertEquals("NEEDS_REVIEW", evaluator.evaluate(response).status());
        assertTrue(evaluator.evaluate(response).reviewRequired().contains("factual_accuracy"));
    }
    @Test void missingAnswerFails() throws Exception {
        assertEquals("FAIL", evaluator.evaluate(JsonSupport.mapper().readTree("{}")).status());
    }
    @Test void blankAnswerFails() throws Exception {
        assertEquals("FAIL", evaluator.evaluate(JsonSupport.mapper().readTree("{\"answer\":\" \"}")).status());
    }
}