package com.garmin.analytics.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;

final class ChatTestSupport {

  static final String WEEK = """
      {"type":"EXPLICIT","startDate":"2026-11-09","endDate":"2026-11-15","days":0}
      """;
  static final String LAST_SEVEN = """
      {"type":"LAST_N_DAYS","startDate":"","endDate":"","days":7}
      """;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
  static final GarminChatProperties PROPERTIES = new GarminChatProperties(
      123L, ZoneId.of("Europe/Oslo"), 8192, 1024);

  private ChatTestSupport() {}

  static QuestionDateResolver dates(Clock clock, GarminChatProperties properties)
      throws IOException {
    return new QuestionDateResolver(clock, properties, new ObjectMapper(),
        new ClassPathResource("prompts/date-selection-system.txt"));
  }

  static GarminChatService service(DailySummaryRepository repository, ChatModel model)
      throws IOException {
    return service(repository, model, () -> true);
  }

  static GarminChatService service(
      DailySummaryRepository repository, ChatModel model, ChatModelAvailability availability)
      throws IOException {
    return new GarminChatService(repository, model, availability, dates(CLOCK, PROPERTIES),
        new PromptBudget(PROPERTIES), PROPERTIES,
        new ClassPathResource("prompts/garmin-daily-summary-system.txt"));
  }

  static ChatModel model(String selection, Function<Prompt, ChatResponse> answer) {
    return prompt -> isSelection(prompt) ? response(selection) : answer.apply(prompt);
  }

  static boolean isSelection(Prompt prompt) {
    return prompt.getOptions() instanceof OllamaChatOptions options && options.getFormat() != null;
  }

  static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
