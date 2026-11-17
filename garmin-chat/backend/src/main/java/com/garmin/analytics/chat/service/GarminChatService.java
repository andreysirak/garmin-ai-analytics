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
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

@Service
public class GarminChatService {

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
    var referenceDate = dates.referenceDate();
    var selectionPrompt = dates.intentPrompt(question, referenceDate);
    if (!budget.fits(selectionPrompt)) {
      return new ChatAnswer("Your question is too long for the model context. "
          + "Please shorten it and specify a metric, aggregation and clear period.", null, null);
    }
    if (!modelAvailability.isAvailable()) {
      throw new ChatModelUnavailableException(null);
    }
    var selection = dates.resolveIntent(askModel(selectionPrompt), referenceDate);
    if (!selection.accepted()) {
      return new ChatAnswer(selection.message(), null, null);
    }
    var intent = selection.intent();
    var range = intent.range();
    var startDate = range.startDate();
    var endDate = range.endDate();
    var aggregates = repository.aggregate(intent);
    validateResults(intent, aggregates);
    if (aggregates.stream().allMatch(result -> result.coverage().summaryDays() == 0)) {
      return new ChatAnswer("No Garmin daily summaries are available. "
          + "The daily-summary data is missing; unknown readings do not mean zero.",
          startDate, endDate);
    }
    if (aggregates.stream().allMatch(result -> result.value() == null)) {
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
      return new ChatAnswer("The complete evidence and question exceed the model context "
          + "budget. Please choose a shorter period or fewer metrics. "
          + "No data was truncated or sent for an answer.",
          startDate, endDate);
    }
    return new ChatAnswer(askModel(prompt), startDate, endDate);
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

  private String askModel(Prompt prompt) {
    try {
      var response = model.call(prompt);
      if (response == null || response.getResult() == null
          || response.getResult().getOutput() == null
          || response.getResult().getOutput().getText() == null
          || response.getResult().getOutput().getText().isBlank()) {
        throw new IllegalStateException("Model returned no text");
      }
      return response.getResult().getOutput().getText();
    } catch (RuntimeException failure) {
      throw new ChatModelUnavailableException(failure);
    }
  }
}
