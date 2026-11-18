package com.garmin.analytics.chat.ai.prompt;

import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class GarminChatPromptFactory {

  private final GarminChatProperties properties;
  private final String systemTemplate;
  private final String periodInstructions;
  private final String metricCatalog = Metric.catalog();

  public GarminChatPromptFactory(GarminChatProperties properties,
      @Value("classpath:prompts/garmin-chat-system.txt") Resource systemPrompt,
      @Value("classpath:prompts/period-selection-rules.txt") Resource periodRules)
      throws IOException {
    this.properties = properties;
    this.systemTemplate = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    this.periodInstructions = periodRules.getContentAsString(StandardCharsets.UTF_8);
  }

  public Prompt create(String question, LocalDate referenceDate, ToolCallback[] tools) {
    var system = new SystemMessage(systemTemplate.formatted(metricCatalog, periodInstructions,
        referenceDate, properties.timezone(), properties.maxQueryDays()));
    var options = OllamaChatOptions.builder()
        .numPredict(properties.responseTokens())
        .toolCallbacks(tools)
        .build();
    return new Prompt(List.of(system, new UserMessage(question)), options);
  }
}
