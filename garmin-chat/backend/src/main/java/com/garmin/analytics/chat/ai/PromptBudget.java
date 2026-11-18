package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.config.GarminChatProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.prompt.Prompt;
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
    for (var message : prompt.getInstructions()) {
      // One token per UTF-8 byte deliberately overestimates normal text and Garmin JSON.
      estimate += message.getText().getBytes(StandardCharsets.UTF_8).length;
    }
    return estimate <= properties.contextTokens();
  }
}
