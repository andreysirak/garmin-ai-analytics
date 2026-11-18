package com.garmin.analytics.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ChatModelUnavailableException;
import com.garmin.analytics.chat.ai.PromptBudget;
import com.garmin.analytics.chat.ai.QuestionDateResolver;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.ChatAnswer;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.model.QueryIntent;
import com.garmin.analytics.chat.repository.DailySummaryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

@Service
public class GarminChatService {

  private static final Logger log = LoggerFactory.getLogger(GarminChatService.class);
  private static final Logger diagnostics = LoggerFactory.getLogger("garmin.chat.diagnostics");

  private final DailySummaryRepository repository;
  private final ChatModel model;
  private final ChatModelAvailability modelAvailability;
  private final QuestionDateResolver dates;
  private final PromptBudget budget;
  private final GarminChatProperties properties;
  private final ObjectMapper json;
  private final String systemPrompt;

  public GarminChatService(
      DailySummaryRepository repository,
      ChatModel model,
      ChatModelAvailability modelAvailability,
      QuestionDateResolver dates,
      PromptBudget budget,
      GarminChatProperties properties,
      ObjectMapper json,
      @Value("classpath:prompts/garmin-evidence-system.txt") Resource systemPrompt)
      throws IOException {
    this.repository = repository;
    this.model = model;
    this.modelAvailability = modelAvailability;
    this.dates = dates;
    this.budget = budget;
    this.properties = properties;
    this.json = json;
    this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
  }

  public ChatAnswer answer(String question) {
    long started = System.nanoTime();
    String previousId = MDC.get("chatRequestId");
    MDC.put("chatRequestId", UUID.randomUUID().toString());
    log.info("chat_started");
    try {
      var answer = answerQuestion(question);
      log.info("chat_completed elapsed_ms={}", elapsedMillis(started));
      return answer;
    } catch (RuntimeException failure) {
      // Exception messages may contain connection details or provider response bodies.
      log.warn("chat_failed error_type={} elapsed_ms={}",
          failure.getClass().getSimpleName(), elapsedMillis(started));
      throw failure;
    } finally {
      if (previousId == null) {
        MDC.remove("chatRequestId");
      } else {
        MDC.put("chatRequestId", previousId);
      }
    }
  }

  private ChatAnswer answerQuestion(String question) {
    var referenceDate = dates.referenceDate();
    var selectionPrompt = dates.intentPrompt(question, referenceDate);
    if (!budget.fits(selectionPrompt)) {
      log.info("chat_rejected reason=intent_context_budget");
      return new ChatAnswer("Your question is too long for the model context. "
          + "Please shorten it and specify a metric, aggregation and clear period.", null, null);
    }
    if (!modelAvailability.isAvailable()) {
      log.warn("model_unavailable stage=availability_check");
      throw new ChatModelUnavailableException(null);
    }
    var selection = dates.resolveIntent(askModel("intent", selectionPrompt), referenceDate);
    if (!selection.accepted()) {
      log.info("intent_rejected reason={}", selection.message());
      return new ChatAnswer(selection.message(), null, null);
    }
    var intent = selection.intent();
    var range = intent.range();
    var startDate = range.startDate();
    var endDate = range.endDate();
    diagnostic("sql_request", intent);
    long queryStarted = System.nanoTime();
    List<MetricAggregation> aggregates;
    try {
      aggregates = repository.aggregate(intent);
      validateResults(intent, aggregates);
    } catch (RuntimeException failure) {
      log.warn("sql_failed query=metric-aggregation elapsed_ms={} error_type={}",
          elapsedMillis(queryStarted), failure.getClass().getSimpleName());
      throw failure;
    }
    log.info("sql_completed query=metric-aggregation metrics={} aggregation={} days={} "
            + "elapsed_ms={}", intent.metrics(), intent.aggregation(),
        ChronoUnit.DAYS.between(startDate, endDate) + 1, elapsedMillis(queryStarted));
    diagnostic("sql_evidence", aggregates);
    if (aggregates.stream().allMatch(result -> result.coverage().summaryDays() == 0)) {
      log.info("chat_no_data reason=no_summaries");
      return new ChatAnswer("No Garmin daily summaries are available. "
          + "The daily-summary data is missing; unknown readings do not mean zero.",
          startDate, endDate);
    }
    if (aggregates.stream().allMatch(result -> result.value() == null)) {
      log.info("chat_no_data reason=no_numeric_readings");
      return new ChatAnswer("No valid numeric readings are available for the requested metrics "
          + "in this period. Missing summaries, absent or null fields, and invalid types "
          + "cannot answer this question. Unknown readings do not mean zero.", startDate, endDate);
    }
    var evidence = new QueryEvidence(question, intent.aggregation(), range,
        aggregates.stream().map(result -> new QueryEvidence.MetricValue(
            result.metric().field(), result.value(), result.unit(), result.coverage())).toList());
    var prompt = new Prompt(List.of(new SystemMessage(systemPrompt),
        new UserMessage(serialize(evidence))), OllamaChatOptions.builder()
        .numPredict(properties.responseTokens()).temperature(0.0).build());
    if (!budget.fits(prompt)) {
      log.info("chat_rejected reason=explanation_context_budget");
      return new ChatAnswer("The complete evidence and question exceed the model context "
          + "budget. Please choose a shorter period or fewer metrics. "
          + "No data was truncated or sent for an answer.",
          startDate, endDate);
    }
    return new ChatAnswer(askModel("explanation", prompt), startDate, endDate);
  }

  private void validateResults(QueryIntent intent, List<MetricAggregation> results) {
    long expectedDays = ChronoUnit.DAYS.between(
        intent.range().startDate(), intent.range().endDate()) + 1;
    if (results == null || results.size() != intent.metrics().size()) {
      throw new DataRetrievalFailureException("Garmin aggregation results are incomplete");
    }
    for (int index = 0; index < results.size(); index++) {
      var result = results.get(index);
      var metric = intent.metrics().get(index);
      if (result == null || result.metric() != metric
          || result.aggregation() != intent.aggregation()
          || !intent.range().equals(result.range()) || !metric.unit().equals(result.unit())
          || result.coverage() == null || result.coverage().expectedDays() != expectedDays
          || (result.value() == null) != (result.coverage().daysWithData() == 0)) {
        throw new DataRetrievalFailureException(
            "Garmin aggregation results do not match the query");
      }
    }
  }

  private String serialize(QueryEvidence evidence) {
    try {
      return json.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .writeValueAsString(evidence);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Could not serialize Garmin aggregate evidence", failure);
    }
  }

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private void diagnostic(String event, Object value) {
    if (diagnostics.isDebugEnabled()) {
      try {
        // JSON escapes newlines, keeping each diagnostic event on one physical line.
        diagnostics.debug("event={} data={}", event,
            json.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(value));
      } catch (JsonProcessingException failure) {
        log.warn("diagnostic_serialization_failed event={}", event);
      }
    }
  }

  private static String promptFingerprint(Prompt prompt) {
    // Fingerprint system instructions/catalog/reference date, never the personal user message.
    String system = prompt.getInstructions().stream()
        .filter(SystemMessage.class::isInstance).map(message -> message.getText())
        .collect(java.util.stream.Collectors.joining("\n"));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(system.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private String askModel(String stage, Prompt prompt) {
    long started = System.nanoTime();
    int characters = prompt.getInstructions().stream()
        .mapToInt(message -> message.getText().length()).sum();
    log.info("model_started stage={} prompt_chars={} context_tokens={} response_tokens={} "
            + "prompt_sha256={}", stage, characters, properties.contextTokens(),
        ((OllamaChatOptions) prompt.getOptions()).getNumPredict(), promptFingerprint(prompt));
    if (diagnostics.isDebugEnabled()) {
      diagnostic(stage + "_request", Map.of(
          "messages", prompt.getInstructions().stream().map(message -> Map.of(
              "role", message.getMessageType().name(), "text", message.getText())).toList(),
          "options", prompt.getOptions()));
    }
    try {
      var response = model.call(prompt);
      if (response == null || response.getResult() == null
          || response.getResult().getOutput() == null
          || response.getResult().getOutput().getText() == null
          || response.getResult().getOutput().getText().isBlank()) {
        throw new IllegalStateException("Model returned no text");
      }
      var usage = response.getMetadata().getUsage();
      boolean usageKnown = usage != null && !(usage instanceof EmptyUsage);
      log.info("model_completed stage={} elapsed_ms={} model={} prompt_tokens={} "
              + "completion_tokens={} total_tokens={} finish_reason={}",
          stage, elapsedMillis(started), response.getMetadata().getModel(),
          usageKnown ? usage.getPromptTokens() : null,
          usageKnown ? usage.getCompletionTokens() : null,
          usageKnown ? usage.getTotalTokens() : null,
          response.getResult().getMetadata().getFinishReason());
      String text = response.getResult().getOutput().getText();
      diagnostic(stage + "_response", text);
      return text;
    } catch (RuntimeException failure) {
      log.warn("model_failed stage={} elapsed_ms={} error_type={}",
          stage, elapsedMillis(started), failure.getClass().getSimpleName());
      throw new ChatModelUnavailableException(failure);
    }
  }
}
