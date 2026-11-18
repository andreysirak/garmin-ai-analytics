package com.garmin.analytics.chat.service;

import static com.garmin.analytics.chat.support.ChatTestSupport.WEEK;
import static com.garmin.analytics.chat.support.ChatTestSupport.isSelection;
import static com.garmin.analytics.chat.support.ChatTestSupport.response;
import static com.garmin.analytics.chat.support.ChatTestSupport.result;
import static com.garmin.analytics.chat.support.ChatTestSupport.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.garmin.analytics.chat.ai.ChatModelUnavailableException;
import com.garmin.analytics.chat.ai.QuestionDateResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;

class ChatDiagnosticsTest {

  @Test
  void correlatesBothCallsAndSqlWithoutLoggingQuestionOrModelText() throws Exception {
    try (var normal = new Capture(GarminChatService.class.getName(), Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      var requestIds = new ArrayList<String>();
      var service = service(query -> List.of(result(query, "123")), prompt -> {
        requestIds.add(MDC.get("chatRequestId"));
        return response(isSelection(prompt) ? WEEK : "private model answer");
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
      assertThat(normal.text()).contains("model_started stage=intent", "stage=explanation",
          "sql_completed", "chat_completed", "elapsed_ms=", "prompt_sha256=",
          "prompt_tokens=null", "completion_tokens=null")
          .doesNotContain("private user question", "private model answer", "2026-11-09");
      assertThat(trace.events.list).isEmpty();
    }
  }

  @Test
  void optInCapturesExactMessagesResponsesAndSqlEvidenceAsEscapedJson() throws Exception {
    try (var trace = new Capture("garmin.chat.diagnostics", Level.DEBUG)) {
      var service = service(query -> List.of(result(query, "123")), prompt ->
          response(isSelection(prompt) ? WEEK : "private answer\nsecond line"));
      service.answer("private question\nsecond line");
      assertThat(trace.text()).contains("event=intent_request", "event=intent_response",
          "event=sql_request", "event=sql_evidence", "event=explanation_request",
          "event=explanation_response", "private question\\nsecond line",
          "private answer\\nsecond line", "123")
          .doesNotContain("profileId", "postgres_password");
      assertThat(trace.events.list).allSatisfy(event ->
          assertThat(event.getFormattedMessage()).doesNotContain("\n"));
      assertThat(MDC.get("chatRequestId")).isNull();
    }
  }

  @Test
  void modelAndSqlFailuresLogStageAndTypeWithoutExceptionContents() throws Exception {
    try (var normal = new Capture(GarminChatService.class.getName(), Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      var modelFailure = service(query -> { throw new AssertionError("Must not query"); },
          prompt -> { throw new IllegalStateException("password=private-value"); });
      assertThatThrownBy(() -> modelFailure.answer("private question"))
          .isInstanceOf(ChatModelUnavailableException.class);
      var sqlFailure = service(query -> {
        throw new DataAccessResourceFailureException("password=private-value");
      }, prompt -> response(WEEK));
      assertThatThrownBy(() -> sqlFailure.answer("private question"))
          .isInstanceOf(DataAccessResourceFailureException.class);
      assertThat(normal.text()).contains("model_failed stage=intent", "sql_failed",
          "error_type=IllegalStateException", "chat_failed")
          .doesNotContain("private-value", "private question");
      assertThat(MDC.get("chatRequestId")).isNull();
      assertThat(trace.events.list).isEmpty();
    }
  }

  @Test
  void invalidPeriodExplainsValidationFailureWithoutQueryingOrLeakingModelJson() throws Exception {
    try (var normal = new Capture(QuestionDateResolver.class.getName(), Level.INFO);
         var trace = new Capture("garmin.chat.diagnostics", Level.OFF)) {
      var service = service(query -> { throw new AssertionError("Must not query"); },
          prompt -> response(WEEK.replace("2026-11-09", "private-invalid-date")));
      assertThat(service.answer("private question").answer()).contains("valid, ordered dates");
      assertThat(normal.text()).contains("reason=invalid_period", "type=EXPLICIT")
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
