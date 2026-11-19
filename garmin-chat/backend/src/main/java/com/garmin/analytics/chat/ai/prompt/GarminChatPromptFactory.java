package com.garmin.analytics.chat.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.garmin.analytics.chat.ai.routing.RoutingOutput;
import com.garmin.analytics.chat.config.GarminChatProperties;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class GarminChatPromptFactory {

  private final GarminChatProperties properties;
  private final String systemTemplate;
  private final String periodInstructions;
  private final String routingInstructions;
  private final String answerInstructions;
  private final Map<String, Object> routingSchema =
      new BeanOutputConverter<>(RoutingOutput.class) {
        @Override
        protected void postProcessSchema(JsonNode schema) {
          // Spring AI 1.1.8 marks all fields required by default. Branch fields are conditional.
          ((ObjectNode) schema).putArray("required").add("route");
        }
      }.getJsonSchemaMap();
  private final String metricCatalog = Metric.catalog();

  public GarminChatPromptFactory(GarminChatProperties properties,
      @Value("classpath:prompts/garmin-chat-system.txt") Resource systemPrompt,
      @Value("classpath:prompts/period-selection-rules.txt") Resource periodRules,
      @Value("classpath:prompts/garmin-routing-system.txt") Resource routingPrompt,
      @Value("classpath:prompts/garmin-answer-instructions.txt") Resource answerPrompt)
      throws IOException {
    this.properties = properties;
    this.systemTemplate = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    this.periodInstructions = periodRules.getContentAsString(StandardCharsets.UTF_8);
    this.routingInstructions = routingPrompt.getContentAsString(StandardCharsets.UTF_8);
    this.answerInstructions = answerPrompt.getContentAsString(StandardCharsets.UTF_8);
  }

  public Prompt routing(String question, LocalDate referenceDate) {
    var system = routingInstructions + "\nSupported Garmin metrics:\n" + metricCatalog + "\n\n"
        + periodInstructions + "\nReference date: " + referenceDate + " (" + properties.timezone()
        + "). Maximum period: " + properties.maxQueryDays() + " inclusive days.";
    var options = OllamaChatOptions.builder()
        .numPredict(properties.responseTokens())
        .format(routingSchema)
        .internalToolExecutionEnabled(false)
        .build();
    return new Prompt(List.of(new SystemMessage(system), new UserMessage(question)), options);
  }

  public Prompt analysis(String question, AnalyticsSelection selection, ToolCallback[] tools) {
    var system = systemTemplate.formatted(JsonParser.toJson(selection), answerInstructions);
    var options = OllamaChatOptions.builder()
        .numPredict(properties.responseTokens())
        .toolCallbacks(tools)
        .build();
    return new Prompt(List.of(new SystemMessage(system), new UserMessage(question)), options);
  }
}
