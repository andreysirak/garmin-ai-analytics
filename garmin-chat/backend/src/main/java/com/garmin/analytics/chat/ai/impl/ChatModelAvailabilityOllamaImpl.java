package com.garmin.analytics.chat.ai.impl;

import com.garmin.analytics.chat.ai.ChatModelAvailability;
import com.garmin.analytics.chat.ai.ModelTransport;
import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ollamaModel")
public class ChatModelAvailabilityOllamaImpl implements ChatModelAvailability, HealthIndicator {

  private final OllamaApi api;
  private final String model;

  public ChatModelAvailabilityOllamaImpl(
      OllamaApi api, @Value("${spring.ai.ollama.chat.options.model}") String model) {
    this.api = api;
    this.model = model;
  }

  @Override
  public Status status() {
    var shown = ModelTransport.call(() -> api.showModel(new OllamaApi.ShowModelRequest(model)));
    if (shown == null) {
      return Status.UNAVAILABLE;
    }
    return shown.capabilities() != null && shown.capabilities().contains("tools")
        ? Status.AVAILABLE : Status.TOOLS_UNSUPPORTED;
  }

  @Override
  public Health health() {
    var status = healthStatus();
    return (status == Status.AVAILABLE ? Health.up() : Health.down())
        .withDetail("model", model)
        .withDetail("initialization", switch (status) {
          case AVAILABLE -> "available";
          case UNAVAILABLE -> "initializing or unavailable";
          case TOOLS_UNSUPPORTED -> "tool calling capability not reported";
        })
        .build();
  }

  private Status healthStatus() {
    try {
      return status();
    } catch (ChatModelUnavailableException | ChatRequestTimeoutException
             | ChatRequestInterruptedException unavailable) {
      return Status.UNAVAILABLE;
    }
  }
}
