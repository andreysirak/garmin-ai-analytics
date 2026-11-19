package com.garmin.analytics.chat.ai.prompt;

import com.garmin.analytics.chat.config.GarminChatProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.stereotype.Component;

@Component
public class PromptBudget {

  // Reserve space for role delimiters, chat templates and tokenizer-specific overhead.
  private static final int TEMPLATE_ALLOWANCE = 512;
  private final GarminChatProperties properties;

  public PromptBudget(GarminChatProperties properties) {
    if ((long) properties.responseTokens() + TEMPLATE_ALLOWANCE >= properties.contextTokens()) {
      throw new IllegalArgumentException(
          "Context must exceed response reserve plus template allowance");
    }
    this.properties = properties;
  }

  public boolean fits(Prompt prompt) {
    long estimate = (long) properties.responseTokens() + TEMPLATE_ALLOWANCE;
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      for (var callback : options.getToolCallbacks()) {
        var tool = callback.getToolDefinition();
        estimate += bytes(tool.name()) + bytes(tool.description()) + bytes(tool.inputSchema());
      }
    }
    if (prompt.getOptions() instanceof OllamaChatOptions options && options.getFormat() != null) {
      estimate += bytes(JsonParser.toJson(options.getFormat()));
    }
    for (var message : prompt.getInstructions()) {
      estimate += bytes(message.getText());
      if (message instanceof AssistantMessage assistant) {
        for (var call : assistant.getToolCalls()) {
          estimate += bytes(call.name()) + bytes(call.arguments());
        }
      } else if (message instanceof ToolResponseMessage tool) {
        for (var response : tool.getResponses()) {
          estimate += bytes(response.responseData());
        }
      }
    }
    return estimate <= properties.contextTokens();
  }

  // One token per UTF-8 byte conservatively estimates text and JSON.
  private long bytes(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }
}
