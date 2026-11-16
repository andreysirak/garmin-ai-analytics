package com.garmin.analytics.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class GarminChatService {

  private final DailySummaryRepository repository;
  private final ChatModel model;
  private final ChatModelAvailability modelAvailability;
  private final QuestionDateResolver dates;
  private final PromptBudget budget;
  private final GarminChatProperties properties;
  private final String systemPrompt;

  public GarminChatService(
      DailySummaryRepository repository,
      ChatModel model,
      ChatModelAvailability modelAvailability,
      QuestionDateResolver dates,
      PromptBudget budget,
      GarminChatProperties properties,
      @Value("classpath:prompts/garmin-daily-summary-system.txt") Resource systemPrompt)
      throws IOException {
    this.repository = repository;
    this.model = model;
    this.modelAvailability = modelAvailability;
    this.dates = dates;
    this.budget = budget;
    this.properties = properties;
    this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
  }

  public ChatAnswer answer(String question) {
    var referenceDate = dates.referenceDate();
    var selectionPrompt = dates.prompt(question, referenceDate);
    if (!budget.fits(selectionPrompt)) {
      return new ChatAnswer("Your question is too long for the model context. "
          + "Please shorten it and include a clear date range.", null, null);
    }
    if (!modelAvailability.isAvailable()) {
      throw new ChatModelUnavailableException(null);
    }
    var selection = dates.resolve(askModel(selectionPrompt), referenceDate);
    if (selection.isEmpty()) {
      return new ChatAnswer("Which dates should I use? "
          + "Please resend your question with a date range including the year, "
          + "or a clear period such as last seven days. "
          + "Dates must be valid, ordered, and no later than today.", null, null);
    }
    var range = selection.get();
    var startDate = range.startDate();
    var endDate = range.endDate();
    var summaries = repository.findSummaries(properties.profileId(), startDate, endDate);
    if (summaries.isEmpty()) {
      return new ChatAnswer("No Garmin daily summaries are available. "
          + "The daily-summary data is missing; unknown readings do not mean zero.",
          startDate, endDate);
    }
    var evidence = new StringBuilder("Garmin daily-summary JSON for ")
        .append(startDate).append(" through ").append(endDate)
        .append(" (inclusive).\nDates absent for a profile are unknown. ")
        .append("Keep profiles separate; do not combine their readings.\n");
    for (var summary : summaries) {
      evidence.append("\nProfile ID: ").append(summary.profileId())
          .append(", date: ").append(summary.date()).append('\n')
          .append(summary.payload()).append('\n');
    }
    evidence.append("\nOriginal question (verbatim):\n").append(question);
    var prompt = new Prompt(List.of(new SystemMessage(systemPrompt),
        new UserMessage(evidence.toString())), OllamaChatOptions.builder()
        .numPredict(properties.responseTokens()).build());
    if (!budget.fits(prompt)) {
      return new ChatAnswer("The complete summaries for this period exceed the model context "
          + "budget. Please choose a shorter period. No data was truncated or sent for an answer.",
          startDate, endDate);
    }
    return new ChatAnswer(askModel(prompt), startDate, endDate);
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
