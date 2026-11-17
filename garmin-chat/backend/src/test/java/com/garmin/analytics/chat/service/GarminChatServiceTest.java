package com.garmin.analytics.chat.service;

import static com.garmin.analytics.chat.support.ChatTestSupport.LAST_SEVEN;
import static com.garmin.analytics.chat.support.ChatTestSupport.WEEK;
import static com.garmin.analytics.chat.support.ChatTestSupport.intent;
import static com.garmin.analytics.chat.support.ChatTestSupport.isSelection;
import static com.garmin.analytics.chat.support.ChatTestSupport.model;
import static com.garmin.analytics.chat.support.ChatTestSupport.rejected;
import static com.garmin.analytics.chat.support.ChatTestSupport.response;
import static com.garmin.analytics.chat.support.ChatTestSupport.result;
import static com.garmin.analytics.chat.support.ChatTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.chat.ai.ChatModelUnavailableException;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.DateRange;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;

class GarminChatServiceTest {

  private final ObjectMapper json = new ObjectMapper();
  private final LocalDate start = LocalDate.of(2026, 11, 9);
  private final LocalDate end = LocalDate.of(2026, 11, 15);

  @Test
  void sendsOnlyCompactEvidenceAndTheVerbatimQuestionAndKeepsRecordedZero() throws Exception {
    String question = "  Сколько шагов с 2026-11-09 по 2026-11-15? {keep}\n  ";
    var calls = new ArrayList<Prompt>();
    var service = service(query -> {
      assertThat(query.metrics()).containsExactly(Metric.TOTAL_STEPS);
      assertThat(query.aggregation()).isEqualTo(Aggregation.SUM);
      assertThat(query.range()).isEqualTo(new DateRange(start, end));
      return List.of(result(query, "0"));
    }, prompt -> {
      calls.add(prompt);
      if (isSelection(prompt)) {
        assertThat(prompt.getInstructions().getLast().getText()).isEqualTo(question);
        return response(WEEK);
      }
      return response("За указанный период записано 0 шагов.");
    });

    assertThat(service.answer(question))
        .isEqualTo(new ChatAnswer("За указанный период записано 0 шагов.", start, end));
    assertThat(calls).hasSize(2);
    var explanation = calls.get(1);
    assertThat(explanation.getInstructions()).hasSize(2);
    assertThat(explanation.getInstructions().getFirst()).isInstanceOf(SystemMessage.class);
    assertThat(explanation.getInstructions().getFirst().getText()).contains(
        "PostgreSQL has already performed all calculations", "Do not invent measurements",
        "Sleep duration cannot establish sleep quality", "Do not make medical conclusions",
        "never the total for the entire period", "not a rolling average", "plain text");
    var evidence = evidence(explanation);
    assertThat(evidence.size()).isEqualTo(4);
    assertThat(evidence.get("question").asText()).isEqualTo(question);
    assertThat(evidence.get("aggregation").asText()).isEqualTo("SUM");
    assertThat(evidence.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
    assertThat(evidence.at("/dateRange/endDate").asText()).isEqualTo("2026-11-15");
    assertThat(evidence.get("metrics").size()).isEqualTo(1);
    assertThat(evidence.at("/metrics/0/metric").asText()).isEqualTo("totalSteps");
    assertThat(evidence.at("/metrics/0/value").decimalValue()).isEqualByComparingTo("0");
    assertThat(evidence.at("/metrics/0/unit").asText()).isEqualTo("steps");
    assertThat(evidence.at("/metrics/0/coverage/zeroDays").asInt()).isEqualTo(7);
    assertThat(evidence.at("/metrics/0/coverage/complete").asBoolean()).isTrue();
    assertThat(evidence.toString()).doesNotContain("profileId", "payload", "dailySummaries");
    var options = (OllamaChatOptions) explanation.getOptions();
    assertThat(options.getNumPredict()).isEqualTo(1024);
    assertThat(options.getFormat()).isNull();
  }

  @Test
  void resolvesTheIntentRelativePeriodBeforeAggregationAndExcludesToday() throws Exception {
    var service = service(query -> {
      assertThat(query.range()).isEqualTo(new DateRange(start, end));
      assertThat(query.metrics()).containsExactly(Metric.RESTING_HEART_RATE);
      assertThat(query.aggregation()).isEqualTo(Aggregation.AVG);
      return List.of(result(query, "55.4"));
    }, model(intent("[\"restingHeartRate\"]", "AVG", LAST_SEVEN),
        prompt -> response("Average recorded resting heart rate: 55.4 bpm.")));
    assertThat(service.answer("Average resting heart rate over the last seven days?").endDate())
        .isEqualTo(end);
  }

  @Test
  void retainsAllCoverageCategoriesAndUnknownMetricsAlongsideNumericResults() throws Exception {
    String structured = WEEK.replace("[\"totalSteps\"]", "[\"sleepingSeconds\",\"totalSteps\"]")
        .replace("\"SUM\"", "\"AVG\"");
    var service = service(query -> List.of(
        new MetricAggregation(Metric.SLEEPING_SECONDS, Aggregation.AVG, new BigDecimal("1.5"),
            "hours", query.range(), new DataCoverage(7, 1, 1, 1, 1, 3, 1)),
        new MetricAggregation(Metric.TOTAL_STEPS, Aggregation.AVG, null,
            "steps", query.range(), new DataCoverage(7, 1, 2, 2, 2, 0, 0))),
        model(structured, prompt -> {
          var data = evidence(prompt);
          var sleep = data.at("/metrics/0");
          assertThat(sleep.get("metric").asText()).isEqualTo("sleepingSeconds");
          assertThat(sleep.get("value").decimalValue()).isEqualByComparingTo("1.5");
          assertThat(sleep.get("unit").asText()).isEqualTo("hours");
          var coverage = sleep.get("coverage");
          for (String name : List.of("missingSummaryDays", "missingMetricDays", "nullMetricDays",
              "invalidTypeDays", "zeroDays")) {
            assertThat(coverage.get(name).asInt()).isEqualTo(1);
          }
          assertThat(coverage.get("daysWithData").asInt()).isEqualTo(3);
          assertThat(coverage.get("summaryDays").asInt()).isEqualTo(6);
          assertThat(coverage.get("missingDays").asInt()).isEqualTo(3);
          assertThat(coverage.get("complete").asBoolean()).isFalse();
          assertThat(data.at("/metrics/1/value").isNull()).isTrue();
          assertThat(data.at("/metrics/1/coverage/invalidTypeDays").asInt()).isEqualTo(2);
          return response("Sleep duration averaged 1.5 hours over 3 of 7 days; steps are unknown.");
        }));
    assertThat(service.answer("Average sleep duration and steps last week?").answer())
        .contains("steps are unknown");
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLARIFICATION", "UNSUPPORTED"})
  void rejectedQuestionsSkipSqlAndExplanation(String status) throws Exception {
    var calls = new AtomicInteger();
    var service = service(query -> { throw new AssertionError("No SQL for rejected intent"); },
        prompt -> { calls.incrementAndGet(); return response(rejected(status)); });
    var answer = service.answer("Was my sleep healthy last week?");
    assertThat(answer.startDate()).isNull();
    assertThat(answer.endDate()).isNull();
    assertThat(answer.answer()).contains(status.equals("CLARIFICATION")
        ? "Please specify" : "not supported");
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("invalidIntents")
  void invalidIntentNeverQueriesPostgresOrCallsExplanation(String structured) throws Exception {
    var calls = new AtomicInteger();
    var service = service(query -> { throw new AssertionError("No SQL for invalid intent"); },
        prompt -> { calls.incrementAndGet(); return response(structured); });
    var answer = service.answer("Numeric summary for this period?");
    assertThat(answer.startDate()).isNull();
    assertThat(answer.answer()).isNotBlank();
    assertThat(calls.get()).isEqualTo(1);
  }

  private static Stream<String> invalidIntents() {
    return Stream.of("{}", "not JSON", WEEK.replace("totalSteps", "sleepScore"),
        WEEK.replace("totalSteps", "restingHeartRate"),
        WEEK.replace("\"status\":", "\"profileId\":99,\"status\":"),
        WEEK.replace("2026-11-09", "2026-11-17"),
        WEEK.replace("2026-11-09", "2026-02-29"),
        WEEK.replace("2026-11-09", "2026-10-15"));
  }

  @Test
  void noSummariesReturnsDatesWithoutExplanationCall() throws Exception {
    var service = service(query -> List.of(result(query, null)), model(WEEK, prompt -> {
      throw new AssertionError("No explanation call without summaries");
    }));
    var answer = service.answer("Total steps last week?");
    assertThat(answer.answer()).contains("No Garmin daily summaries", "unknown readings");
    assertThat(answer.startDate()).isEqualTo(start);
    assertThat(answer.endDate()).isEqualTo(end);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "null", "invalid"})
  void allUnknownReadingsAreReportedDirectlyWithoutInventingZero(String category) throws Exception {
    var coverage = new DataCoverage(7, 0, category.equals("missing") ? 7 : 0,
        category.equals("null") ? 7 : 0, category.equals("invalid") ? 7 : 0, 0, 0);
    var service = service(query -> List.of(new MetricAggregation(Metric.TOTAL_STEPS,
        Aggregation.SUM, null, "steps", query.range(), coverage)), model(WEEK, prompt -> {
          throw new AssertionError("No explanation call without numeric data");
        }));
    var answer = service.answer("Total steps last week?");
    assertThat(answer.answer()).contains("No valid numeric readings", "do not mean zero");
    assertThat(answer.startDate()).isEqualTo(start);
    assertThat(answer.endDate()).isEqualTo(end);
  }

  @Test
  void mismatchedRepositoryResultsNeverReachExplanation() throws Exception {
    for (var metric : List.of(Metric.RESTING_HEART_RATE, Metric.SLEEPING_SECONDS)) {
      var service = service(query -> List.of(new MetricAggregation(metric, Aggregation.AVG,
          BigDecimal.ONE, metric.unit(), query.range(), new DataCoverage(7, 0, 0, 0, 0, 7, 0))),
          model(WEEK, prompt -> { throw new AssertionError("No unrequested evidence"); }));
      assertThatThrownBy(() -> service.answer("Total steps last week?"))
          .isInstanceOf(DataRetrievalFailureException.class);
    }
    var empty = service(query -> List.of(), model(WEEK, prompt -> {
      throw new AssertionError("No incomplete results");
    }));
    assertThatThrownBy(() -> empty.answer("Total steps last week?"))
        .isInstanceOf(DataRetrievalFailureException.class);
  }

  @Test
  void oversizedEvidenceSkipsExplanationWithoutTruncation() throws Exception {
    var service = service(query -> List.of(result(query, "1".repeat(20_000))),
        model(WEEK, prompt -> { throw new AssertionError("No oversized explanation call"); }));
    var answer = service.answer("Total steps last week?");
    assertThat(answer.answer()).contains("choose a shorter period", "No data was truncated");
    assertThat(answer.startDate()).isEqualTo(start);
    assertThat(answer.endDate()).isEqualTo(end);
  }

  @Test
  void oversizedQuestionSkipsBothModelCallsAndPostgres() throws Exception {
    var service = service(query -> { throw new AssertionError("No query should happen"); },
        prompt -> { throw new AssertionError("No model call should happen"); });
    assertThat(service.answer("x".repeat(20_000)).answer()).contains("question is too long");
  }

  @Test
  void unavailableModelSkipsInterpretationAndPostgres() throws Exception {
    var service = service(query -> { throw new AssertionError("No query should happen"); },
        prompt -> { throw new AssertionError("No model call should happen"); }, () -> false);
    assertThatThrownBy(() -> service.answer("Total steps last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasMessageContaining("retry shortly");
  }

  @Test
  void interpretationFailureIsRetryableWithoutQuery() throws Exception {
    var failure = new IllegalStateException("synthetic interpretation failure");
    var service = service(query -> { throw new AssertionError("No query should happen"); },
        prompt -> { throw failure; });
    assertThatThrownBy(() -> service.answer("Total steps last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasCause(failure);
  }

  @Test
  void explanationFailureIsRetryable() throws Exception {
    var failure = new IllegalStateException("synthetic explanation failure");
    var service = service(query -> List.of(result(query, "10")), model(WEEK, prompt -> {
      throw failure;
    }));
    assertThatThrownBy(() -> service.answer("Total steps last week?"))
        .isInstanceOf(ChatModelUnavailableException.class).hasCause(failure);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = "   ")
  void absentExplanationTextIsAModelFailure(String text) throws Exception {
    var service = service(query -> List.of(result(query, "10")),
        model(WEEK, prompt -> text == null ? null : response(text)));
    assertThatThrownBy(() -> service.answer("Total steps last week?"))
        .isInstanceOf(ChatModelUnavailableException.class);
  }

  @Test
  void databaseFailureSkipsExplanation() throws Exception {
    var failure = new DataAccessResourceFailureException("synthetic database failure");
    var service = service(query -> { throw failure; }, model(WEEK, prompt -> {
      throw new AssertionError("No explanation should be requested");
    }));
    assertThatThrownBy(() -> service.answer("Total steps last week?")).isSameAs(failure);
  }

  @Test
  void questionsAreIndependentWithExactlyTwoCallsPerSupportedQuestion() throws Exception {
    var prompts = new ArrayList<Prompt>();
    var loads = new AtomicInteger();
    var service = service(query -> {
      loads.incrementAndGet();
      return List.of(result(query, "0"));
    }, prompt -> {
      prompts.add(prompt);
      return response(isSelection(prompt) ? WEEK : "Synthetic explanation");
    });
    service.answer("First question last week?");
    service.answer("Second question last week?");
    assertThat(loads.get()).isEqualTo(2);
    assertThat(prompts).hasSize(4);
    for (var prompt : prompts.subList(2, 4)) {
      assertThat(prompt.getContents()).doesNotContain("First question", "Synthetic explanation");
    }
  }

  private JsonNode evidence(Prompt prompt) {
    try {
      return json.readTree(prompt.getInstructions().getLast().getText());
    } catch (IOException failure) {
      throw new AssertionError("Explanation input must be JSON", failure);
    }
  }
}
