package com.garmin.analytics.chat.diagnostics;

import static com.garmin.analytics.chat.support.ChatTestSupport.WEEK;
import static com.garmin.analytics.chat.support.ChatTestSupport.model;
import static com.garmin.analytics.chat.support.ChatTestSupport.response;
import static com.garmin.analytics.chat.support.ChatTestSupport.result;
import static com.garmin.analytics.chat.support.ChatTestSupport.service;
import static com.garmin.analytics.chat.support.ChatTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.garmin.analytics.chat.ai.tool.GarminToolCallAdvisor;
import com.garmin.analytics.chat.support.ChatTestSupport.AggregationLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.dao.DataAccessResourceFailureException;

class ChatDiagnosticsTest {

  @Test
  void correlatesBothCallsAndSqlWithoutLoggingQuestionOrModelText() throws Exception {
    try (var normal = new Capture("com.garmin.analytics.chat", Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      var requestIds = new ArrayList<String>();
      var service = service((AggregationLoader) query -> List.of(result(query, "123")), prompt -> {
        requestIds.add(MDC.get("chatRequestId"));
        return prompt.getInstructions().getLast() instanceof ToolResponseMessage
            ? response("private model answer") : toolCall(WEEK);
      });
      MDC.put("chatRequestId", "outer-request");
      try {
        service.answer("private user question");
        assertThat(MDC.get("chatRequestId")).isEqualTo("outer-request");
      } finally {
        MDC.remove("chatRequestId");
      }
      assertThat(requestIds).hasSize(2);
      assertThat(requestIds.getFirst()).isNotBlank().isNotEqualTo("outer-request");
      assertThat(requestIds.getLast()).isEqualTo(requestIds.getFirst());
      assertThat(normal.events.list).allSatisfy(event ->
          assertThat(event.getMDCPropertyMap())
              .containsEntry("chatRequestId", requestIds.getFirst()));
      assertThat(normal.text()).contains("model_started turn=1", "model_completed turn=2",
          "tool_execution tool=aggregate_garmin_metrics outcome=completed",
          "sql_completed", "chat_completed", "elapsed_ms=", "prompt_sha256=",
          "prompt_tokens=null", "completion_tokens=null")
          .doesNotContain("private user question", "private model answer", "2026-11-09");
      assertThat(trace.events.list).isEmpty();
    }
  }

  @Test
  void optInCapturesExactMessagesResponsesAndSqlEvidenceAsEscapedJson() throws Exception {
    try (var trace = new Capture("garmin.chat.diagnostics", Level.DEBUG)) {
      var service = service((AggregationLoader) query -> List.of(result(query, "123")),
          model(WEEK, prompt -> response("private answer\nsecond line")));
      service.answer("private question\nsecond line");
      assertThat(trace.text()).contains("event=model_request", "event=model_response",
          "event=sql_request", "event=sql_evidence", "\"turn\":1", "\"turn\":2", "private question\\nsecond line",
          "private answer\\nsecond line", "123")
          .doesNotContain("profileId", "postgres_password");
      assertThat(trace.events.list).allSatisfy(event ->
          assertThat(event.getFormattedMessage()).doesNotContain("\n"));
      assertThat(MDC.get("chatRequestId")).isNull();
    }
  }

  @Test
  void modelAndSqlFailuresLogStageAndTypeWithoutExceptionContents() throws Exception {
    try (var normal = new Capture("com.garmin.analytics.chat", Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      AggregationLoader unused = query -> { throw new AssertionError("Must not query"); };
      var modelFailure = service(unused,
          prompt -> { throw new IllegalStateException("password=private-value"); });
      assertThatThrownBy(() -> modelFailure.answer("private question"))
          .isInstanceOf(IllegalStateException.class);
      var sqlFailure = service((AggregationLoader) query -> {
        throw new DataAccessResourceFailureException("password=private-value");
      }, prompt -> toolCall(WEEK));
      assertThatThrownBy(() -> sqlFailure.answer("private question"))
          .isInstanceOf(DataAccessResourceFailureException.class);
      assertThat(normal.text()).contains("model_failed turn=1", "sql_failed", "outcome=failed",
          "error_type=IllegalStateException", "chat_failed")
          .doesNotContain("private-value", "private question");
      assertThat(MDC.get("chatRequestId")).isNull();
      assertThat(trace.events.list).isEmpty();
    }
  }

  @Test
  void invalidPeriodExplainsValidationFailureWithoutQueryingOrLeakingModelJson() throws Exception {
    try (var normal = new Capture(GarminToolCallAdvisor.class.getName(), Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      AggregationLoader unused = query -> { throw new AssertionError("Must not query"); };
      var service = service(unused, prompt -> toolCall(WEEK.replace("LAST_WEEK", "EXPLICIT")
              .replace("\"startDate\":\"\"", "\"startDate\":\"private-invalid-date\"")));
      assertThat(service.answer("private question").answer()).contains("valid, ordered dates");
      assertThat(normal.text()).contains("outcome=rejected", "error_type=InvalidAnalyticsRequest")
          .doesNotContain("private-invalid-date", "private question");
      assertThat(MDC.get("chatRequestId")).isNull();
    }
  }

  private static final class Capture implements AutoCloseable {
    private final Logger logger;
    private final Level previous;
    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
      @Override
      protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        super.append(event);
      }
    };

    Capture(String name, Level level) {
      logger = (Logger) LoggerFactory.getLogger(name);
      previous = logger.getLevel();
      logger.setLevel(level);
      events.start();
      logger.addAppender(events);
    }

    String text() {
      return events.list.stream().map(ILoggingEvent::getFormattedMessage)
          .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Override
    public void close() {
      logger.detachAppender(events);
      logger.setLevel(previous);
      events.stop();
    }
  }
}
