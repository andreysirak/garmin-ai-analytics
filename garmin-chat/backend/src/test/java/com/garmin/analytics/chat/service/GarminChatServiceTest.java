package com.garmin.analytics.chat.service;

import static com.garmin.analytics.chat.support.ChatTestSupport.JSON;
import static com.garmin.analytics.chat.support.ChatTestSupport.PROPERTIES;
import static com.garmin.analytics.chat.support.ChatTestSupport.WEEK;
import static com.garmin.analytics.chat.support.ChatTestSupport.response;
import static com.garmin.analytics.chat.support.ChatTestSupport.service;
import static com.garmin.analytics.chat.support.ChatTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.garmin.analytics.chat.ai.ChatModelAvailability.Status;
import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import com.garmin.analytics.chat.model.DataCoverage;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import com.garmin.analytics.chat.support.ChatTestSupport;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.dao.DataAccessResourceFailureException;

class GarminChatServiceTest {

  private final DailySummaryRepository repository = mock(DailySummaryRepository.class);

  @ParameterizedTest
  @MethodSource("allowedCalculations")
  void executesEveryAllowedCalculationAndReturnsEvidenceDates(Metric metric, Aggregation operation)
      throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));
    var arguments = WEEK.replace("totalSteps", metric.field()).replace("SUM", operation.name());
    var chat = service(repository, ChatTestSupport.model(arguments, prompt -> {
      assertThat(prompt.getInstructions()).hasSize(4);
      assertThat(prompt.getUserMessage().getText()).isEqualTo("Synthetic question");
      var evidence = evidence(prompt);
      assertThat(evidence.get("aggregation").asText()).isEqualTo(operation.name());
      assertThat(evidence.at("/dateRange/startDate").asText()).isEqualTo("2026-11-09");
      assertThat(evidence.at("/metrics/0/metric").asText()).isEqualTo(metric.field());
      assertThat(evidence.at("/metrics/0/unit").asText()).isEqualTo(metric.unit());
      assertThat(evidence.at("/metrics/0/value").intValue()).isZero();
      assertThat(evidence.at("/metrics/0/coverage/zeroDays").intValue()).isEqualTo(7);
      return response("Recorded zero. Misleading text date: 2099-01-01.");
    }));
    var answer = chat.answer("Synthetic question");
    assertThat(answer.answer()).startsWith("Recorded zero.");
    assertThat(answer.startDate()).isEqualTo("2026-11-09");
    assertThat(answer.endDate()).isEqualTo("2026-11-15");
    verify(repository).aggregate(any());
  }

  static Stream<Arguments> allowedCalculations() {
    return Arrays.stream(Metric.values()).flatMap(metric -> Arrays.stream(Aggregation.values())
        .filter(operation -> metric != Metric.RESTING_HEART_RATE || operation != Aggregation.SUM)
        .map(operation -> Arguments.of(metric, operation)));
  }

  @Test
  void suppliesTheCatalogAndToolDefinitionBeforeSelection() throws IOException {
    var chat = service(repository, prompt -> {
      assertThat(prompt.getSystemMessage().getText()).contains(
          "totalSteps: daily step count (steps)", "work productivity, walking speed or distance",
          "restingHeartRate: daily resting heart rate (bpm)", "resting pulse",
          "rolling seven-day resting-heart-rate", "allowed: AVG, MIN, MAX",
          "activeKilocalories: daily active energy (kcal)", "total calories or food intake",
          "sleepingSeconds: recorded daily-summary sleep duration (hours)",
          "Clipped to calendar-day windows", "primary overnight sleep duration", "sleep quality",
          "Europe/Oslo", "2026-11-16");
      var options = (ToolCallingChatOptions) prompt.getOptions();
      assertThat(options.getInternalToolExecutionEnabled()).isFalse();
      assertThat(options.getToolCallbacks()).isEmpty();
      assertThat(ChatTestSupport.routing(prompt)).isTrue();
      // A schema-valid misroute must not reinterpret work productivity as steps.
      return response(ChatTestSupport.analyze(WEEK));
    });
    var answer = chat.answer("How productively did I work last week?");
    assertThat(answer.answer()).isEqualTo("Do you mean work productivity or physical activity?");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({
      "Average resting heart rate?, PERIOD, What time period should I use?",
      "How did I sleep last week?, METRIC, Which sleep measurement do you mean?",
      "Який пульс у спокої?, PERIOD, За який період?"
  })
  void returnsFocusedClarificationWithoutData(String question, String target, String text)
      throws IOException {
    var answer = service(repository, prompt -> response(ChatTestSupport.clarify(target, text)))
        .answer(question);
    assertThat(answer.answer()).isEqualTo(text);
    assertThat(answer.startDate()).isNull();
    assertThat(answer.endDate()).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsActivityFollowedBySleepEvenIfRouterSelectsAValidScalarQuery() throws IOException {
    var chat = service(repository, prompt -> response(ChatTestSupport.analyze(WEEK)));
    assertThat(chat.answer("Is more activity followed by longer sleep?").answer())
        .contains("cannot determine whether activity is related to later sleep");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "analysis plan", "{\"route\":\"ANALYZE\"}",
      "{\"route\":\"UNSUPPORTED\",\"reason\":\"OTHER\",\"target\":\"PERIOD\"}",
      "{\"route\":\"UNSUPPORTED\",\"route\":\"CLARIFY\",\"target\":\"PERIOD\"," +
          "\"question\":\"Which period?\"}"})
  void rejectsMalformedOrInconsistentRoutingDecisions(String output) throws IOException {
    var answer = service(repository, prompt -> response(output)).answer("Total steps yesterday?");
    assertThat(answer.answer()).contains("couldn't interpret");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void doesNotRelabelLastWeekAsMissingOrCurrentWeek() throws IOException {
    for (String invalid : List.of(ChatTestSupport.clarify("PERIOD", "Which period?"),
        ChatTestSupport.analyze(WEEK.replace("LAST_WEEK", "THIS_WEEK")))) {
      var answer = service(repository, prompt -> response(invalid)).answer("Total steps last week?");
      assertThat(answer.answer()).contains("last week reliably").doesNotContain("Which period?");
    }
    verifyNoInteractions(repository);
  }

  @Test
  void analyzesStepsYesterdayUsingExecutedEvidence() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "4200")));
    var yesterday = WEEK.replace("LAST_WEEK", "YESTERDAY");
    var chat = service(repository, ChatTestSupport.model(yesterday, prompt -> {
      assertThat(evidence(prompt).at("/metrics/0/value").asInt()).isEqualTo(4200);
      return response("You took 4,200 steps yesterday.");
    }));
    var answer = chat.answer("How many steps yesterday?");
    assertThat(answer.answer()).isEqualTo("You took 4,200 steps yesterday.");
    assertThat(answer.startDate()).isEqualTo("2026-11-15");
    assertThat(answer.endDate()).isEqualTo("2026-11-15");
  }

  @Test
  void noDataCompletionUsesCoverageInsteadOfInventedModelReadings() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call -> {
      QueryIntent intent = call.getArgument(0);
      return List.of(new MetricAggregation(Metric.TOTAL_STEPS, Aggregation.SUM, null,
          "steps", intent.range(), new DataCoverage(7, 1, 2, 3, 1, 0, 0)));
    });
    var chat = service(repository, ChatTestSupport.model(WEEK, prompt ->
        response("You took 5000 steps.")));
    var answer = chat.answer("How many steps last week?");
    assertThat(answer.answer()).contains("none contain a numeric reading", "0 of 7 days")
        .doesNotContain("5000");
    assertThat(answer.startDate()).isEqualTo("2026-11-09");
  }

  @Test
  void analyticalPlanWithoutToolEvidenceIsIncomplete() throws IOException {
    var yesterday = WEEK.replace("LAST_WEEK", "YESTERDAY");
    var chat = service(repository, ChatTestSupport.routed(yesterday,
        prompt -> response("We need to calculate your total steps now.")));
    var answer = chat.answer("How many steps yesterday?");
    assertThat(answer.answer()).contains("couldn't complete").doesNotContain("We need");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"activeKilocalories", "AVG", "THIS_WEEK"})
  void rejectsSchemaValidToolArgumentsThatContradictRouting(String changed) throws IOException {
    String arguments = switch (changed) {
      case "AVG" -> WEEK.replace("SUM", changed);
      case "THIS_WEEK" -> WEEK.replace("LAST_WEEK", changed);
      default -> WEEK.replace("totalSteps", changed);
    };
    var chat = service(repository, ChatTestSupport.routed(WEEK, prompt -> toolCall(arguments)));
    assertThat(chat.answer("How many steps last week?").answer()).contains("did not match");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @MethodSource("invalidArguments")
  void rejectsInvalidToolArgumentsBeforeLoadingData(String arguments) throws IOException {
    var chat = service(repository, ChatTestSupport.routed(WEEK, prompt -> toolCall(arguments)));
    var answer = chat.answer("Synthetic question");

    assertThat(answer.answer()).isNotBlank();
    assertThat(answer.startDate()).isNull();
    assertThat(answer.endDate()).isNull();
    verifyNoInteractions(repository);
  }

  static Stream<String> invalidArguments() {
    return Stream.of("", "null", "[]", "{}", WEEK + "{}", WEEK.replace("SUM", "sum"),
        WEEK.replace("SUM", "CORRELATION"), WEEK.replace("\"SUM\"", "1"),
        WEEK.replace("totalSteps", "restingHeartRate"),
        WEEK.replace("totalSteps", "averageRestingHeartRate"),
        WEEK.replace("\"totalSteps\"", "null"), WEEK.replace("\"totalSteps\"", "42"),
        WEEK.replace("\"totalSteps\"", ""),
        WEEK.replace("\"totalSteps\"", "\"totalSteps\",\"totalSteps\""),
        WEEK.replace("\"metrics\":", "\"profileId\":123,\"metrics\":"),
        WEEK.replace("\"days\":0", "\"days\":0,\"days\":0"),
        WEEK.replace("\"days\":0", "\"days\":\"0\""),
        WEEK.replace("\"days\":0", "\"days\":0.0"),
        WEEK.replace("\"days\":0", "\"days\":2147483648"),
        WEEK.replace("\"days\":0", "\"days\":1"),
        WEEK.replace("\"days\":0", "\"days\":0,\"jsonPath\":\"payload\""),
        WEEK.replace("LAST_WEEK", "LAST_YEAR"),
        WEEK.replace("\"startDate\":\"\"", "\"startDate\":null"),
        WEEK.replace("\"startDate\":\"\",", ""));
  }

  @Test
  void forwardsIndependentCoverageAndNullEvidenceAlongsideRecordedZero() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call -> {
      QueryIntent intent = call.getArgument(0);
      return List.of(new MetricAggregation(Metric.TOTAL_STEPS, Aggregation.AVG, BigDecimal.ZERO,
              "steps", intent.range(), new DataCoverage(7, 1, 1, 1, 1, 3, 3)),
          new MetricAggregation(Metric.SLEEPING_SECONDS, Aggregation.AVG, null, "hours",
              intent.range(), new DataCoverage(7, 1, 2, 3, 1, 0, 0)));
    });
    var multiMetric = WEEK.replace("SUM", "AVG")
        .replace("\"totalSteps\"", "\"totalSteps\",\"sleepingSeconds\"");
    var chat = service(repository, ChatTestSupport.model(multiMetric, prompt -> {
      var evidence = evidence(prompt);
      var coverage = evidence.at("/metrics/0/coverage");
      for (var field : List.of("missingSummaryDays", "missingMetricDays", "nullMetricDays",
          "invalidTypeDays")) {
        assertThat(coverage.get(field).intValue()).isEqualTo(1);
      }
      assertThat(coverage.get("daysWithData").intValue()).isEqualTo(3);
      assertThat(coverage.get("zeroDays").intValue()).isEqualTo(3);
      assertThat(coverage.get("complete").booleanValue()).isFalse();
      assertThat(evidence.at("/metrics/0/value").decimalValue()).isEqualTo(BigDecimal.ZERO);
      assertThat(evidence.at("/metrics/1/value").isNull()).isTrue();
      assertThat(evidence.at("/metrics/1/coverage/daysWithData").intValue()).isZero();
      return response("0 steps on average, from 3 of 7 days. Sleep duration is unknown.");
    }));
    assertThat(chat.answer("Average steps and sleep last week?").answer()).contains("3 of 7");
  }

  @Test
  void rejectsAToolBatchBeforeExecutingAnyCall() throws IOException {
    var first = new AssistantMessage.ToolCall("1", "function", ChatTestSupport.TOOL_NAME, WEEK);
    var second = new AssistantMessage.ToolCall("2", "function", ChatTestSupport.TOOL_NAME, WEEK);
    var chat = service(repository, ChatTestSupport.routed(WEEK,
        prompt -> ChatTestSupport.toolCalls(first, second)));

    assertThat(chat.answer("Synthetic question").answer()).contains("one calculation");
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAnUnknownToolWithoutResolvingOrExecutingIt() throws IOException {
    var call = new AssistantMessage.ToolCall("1", "function", "execute_sql", WEEK);
    var chat = service(repository, ChatTestSupport.routed(WEEK,
        prompt -> ChatTestSupport.toolCalls(call)));

    assertThat(chat.answer("Synthetic question").startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void stopsRepeatedToolCallsAfterOneRepositoryExecution() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), null)));
    var calls = new AtomicInteger();
    var chat = service(repository, ChatTestSupport.routed(WEEK, prompt -> {
      calls.incrementAndGet();
      return toolCall(WEEK);
    }));

    assertThat(chat.answer("Synthetic question").answer()).contains("one calculation");
    assertThat(calls.get()).isEqualTo(2);
    verify(repository).aggregate(any());
  }

  @Test
  void neverSendsDatabaseFailuresToTheModelAsToolResults() throws IOException {
    when(repository.aggregate(any()))
        .thenThrow(new DataAccessResourceFailureException("private connection details"));
    var chat = service(repository, ChatTestSupport.model(WEEK, prompt -> {
      throw new AssertionError("A failed query must not reach the final model turn");
    }));

    assertThatThrownBy(() -> chat.answer("Synthetic question"))
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void rejectsModelsWithoutToolCapability() throws IOException {
    var chat = service(repository, prompt -> {
      throw new AssertionError("An incompatible model must not be called");
    }, () -> Status.TOOLS_UNSUPPORTED, PROPERTIES, Duration.ofSeconds(10));

    assertThatThrownBy(() -> chat.answer("Synthetic question"))
        .isInstanceOf(ChatModelUnavailableException.class).hasMessageContaining("tool calling");
    verifyNoInteractions(repository);
  }

  @Test
  void timesOutTheWholeRequestAndCancelsAnInFlightModelCall() throws Exception {
    var interrupted = new CountDownLatch(1);
    var chat = service(repository, prompt -> {
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException failure) {
        interrupted.countDown();
      }
      return toolCall(WEEK);
    }, () -> Status.AVAILABLE, PROPERTIES, Duration.ofSeconds(1));

    assertThatThrownBy(() -> chat.answer("Synthetic question"))
        .isInstanceOf(ChatRequestTimeoutException.class).hasMessageContaining("too long");
    assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    verifyNoInteractions(repository);
  }

  @Test
  void concurrentConversationsKeepTheirToolEvidenceSeparate() throws Exception {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));
    var evidenceReady = new CountDownLatch(2);
    var chat = service(repository, prompt -> {
      String question = prompt.getUserMessage().getText();
      var selection = question.equals("Yesterday") ? WEEK.replace("LAST_WEEK", "YESTERDAY") : WEEK;
      if (ChatTestSupport.routing(prompt)) {
        return response(ChatTestSupport.analyze(selection));
      }
      if (prompt.getInstructions().size() == 2) {
        return toolCall(selection);
      }
      evidenceReady.countDown();
      try {
        assertThat(evidenceReady.await(5, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new AssertionError(failure);
      }
      return response(evidence(prompt).at("/dateRange/startDate").asText());
    });

    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var week = callers.submit(() -> chat.answer("Last week"));
      var yesterday = callers.submit(() -> chat.answer("Yesterday"));
      assertThat(week.get().answer()).isEqualTo("2026-11-09");
      assertThat(week.get().startDate()).isEqualTo("2026-11-09");
      assertThat(yesterday.get().answer()).isEqualTo("2026-11-15");
      assertThat(yesterday.get().startDate()).isEqualTo("2026-11-15");
    }
  }

  @Test
  void rejectsOversizedInitialContextBeforeAnyOllamaOrDatabaseAccess() throws IOException {
    var chat = service(repository, prompt -> {
      throw new AssertionError("Oversized input must not reach the model");
    }, () -> { throw new AssertionError("Oversized input must not reach Ollama"); },
        PROPERTIES, Duration.ofSeconds(10));

    assertThat(chat.answer("x".repeat(8192)).answer()).contains("too long");
    verifyNoInteractions(repository);
  }

  @Test
  void neverTruncatesOversizedToolEvidence() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "9".repeat(6500))));
    var chat = service(repository, ChatTestSupport.model(WEEK, prompt -> {
      throw new AssertionError("Partial evidence must not reach the model");
    }));

    var answer = chat.answer("Total steps last week?");
    assertThat(answer.answer()).contains("complete question and Garmin evidence exceed");
    assertThat(answer.startDate()).isEqualTo(LocalDate.parse("2026-11-09"));
  }

  private static JsonNode evidence(Prompt prompt) {
    try {
      return JSON.readTree(ChatTestSupport.toolEvidence(prompt));
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
