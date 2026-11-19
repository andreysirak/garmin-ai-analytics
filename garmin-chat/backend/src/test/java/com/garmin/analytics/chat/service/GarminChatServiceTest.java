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
      assertThat(prompt.getInstructions()).hasSize(3);
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
      return response(ChatTestSupport.clarify("METRIC", "Which activity metric do you mean?"));
    });
    var answer = chat.answer("How active was I last week?");
    assertThat(answer.answer()).isEqualTo("Which activity metric do you mean?");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @CsvSource({
      "Average resting heart rate?, PERIOD, What time period should I use?",
      "How did I sleep last week?, METRIC, Which sleep measurement do you mean?",
      "Який пульс у спокої?, PERIOD, За який період?",
      "How many steps?, PERIOD, Please specify the period e.g. yesterday",
      "how productive i was last week ?, GOAL, Do you mean work productivity or physical activity?"
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
  void handlesAnUnsupportedMetricFromStructuredRouting() throws IOException {
    var chat = service(repository, prompt -> response(
        ChatTestSupport.unsupported("UNSUPPORTED_METRIC")));
    assertThat(chat.answer("What was my average walking speed last week?").answer())
        .contains("does not have a supported metric");
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "analysis plan",
      "{\"route\":\"UNSUPPORTED\",\"route\":\"CLARIFY\",\"target\":\"PERIOD\","
          + "\"question\":\"Which period?\"}"})
  void reportsMalformedRoutingOutput(String output) throws IOException {
    var answer = service(repository, prompt -> response(output)).answer("Total steps yesterday?");
    assertThat(answer.answer()).contains("did not return a valid routing response");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"route\":\"ANALYZE\"}",
      "{\"route\":\"UNSUPPORTED\",\"reason\":\"OTHER\",\"target\":\"PERIOD\"}",
      "{\"route\":\"CLARIFY\",\"target\":\"PERIOD\",\"question\":\" \"}"})
  void reportsInconsistentRoutingDecisions(String output) throws IOException {
    var answer = service(repository, prompt -> response(output)).answer("Total steps yesterday?");
    assertThat(answer.answer()).contains("inconsistent routing decision");
    assertThat(answer.startDate()).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void delegatesRoutedPeriodValidationToAnalytics() throws IOException {
    var invalidPeriod = WEEK.replace("\"days\":0", "\"days\":7");
    var answer = service(repository, prompt -> response(ChatTestSupport.analyze(invalidPeriod)))
        .answer("Total steps last week?");
    assertThat(answer.answer()).contains("The model selected an invalid analysis");
    assertThat(answer.startDate()).isNull();
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
    var chat = service(repository, ChatTestSupport.model(WEEK, prompt -> {
      throw new AssertionError("No-data evidence must not require another model call");
    }));
    var answer = chat.answer("How many steps last week?");
    assertThat(answer.answer()).contains("none contain a numeric reading", "0 of 7 days")
        .doesNotContain("5000");
    assertThat(answer.startDate()).isEqualTo("2026-11-09");
  }

  @Test
  void executesAnalysisBeforeExplanationWithoutAskingTheModelToCallATool() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "4200")));
    var calls = new AtomicInteger();
    var compact = WEEK.replace(
        "{\"type\":\"LAST_WEEK\",\"startDate\":\"\",\"endDate\":\"\",\"days\":0}",
        "{\"type\":\"LAST_WEEK\"}");
    var chat = service(repository, ChatTestSupport.routed(compact, prompt -> {
      calls.incrementAndGet();
      verify(repository).aggregate(any());
      assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks()).isEmpty();
      assertThat(evidence(prompt).at("/metrics/0/value").asInt()).isEqualTo(4200);
      return response("You recorded 4,200 steps.");
    }));
    var answer = chat.answer("Total steps last week?");
    assertThat(answer.answer()).isEqualTo("You recorded 4,200 steps.");
    assertThat(answer.startDate()).isEqualTo("2026-11-09");
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("invalidArguments")
  void rejectsInvalidRoutingArgumentsBeforeLoadingData(String arguments) throws IOException {
    var chat = service(repository, prompt -> response(ChatTestSupport.analyze(arguments)));
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
        WEEK.replace("\"startDate\":\"\"", "\"startDate\":\"2026-11-01\""));
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
  void rejectsUnexpectedToolCallsDuringExplanationWithoutExecutingThem() throws IOException {
    when(repository.aggregate(any())).thenAnswer(call ->
        List.of(ChatTestSupport.result(call.getArgument(0), "0")));
    var call = new AssistantMessage.ToolCall("1", "function", "execute_sql", "{}");
    var chat = service(repository, ChatTestSupport.routed(WEEK,
        prompt -> ChatTestSupport.toolCalls(call)));
    var answer = chat.answer("Total steps last week?");
    assertThat(answer.answer()).contains("did not return a completed explanation");
    assertThat(answer.startDate()).isEqualTo("2026-11-09");
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
