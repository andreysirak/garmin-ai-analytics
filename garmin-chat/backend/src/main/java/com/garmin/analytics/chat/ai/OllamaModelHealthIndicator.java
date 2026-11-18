package com.garmin.analytics.chat.ai;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ollamaModel")
public class OllamaModelHealthIndicator implements ChatModelAvailability, HealthIndicator {

  private final OllamaApi api;
  private final String model;

  public OllamaModelHealthIndicator(
      OllamaApi api, @Value("${spring.ai.ollama.chat.options.model}") String model) {
    this.api = api;
    this.model = model;
  }

  @Override
  public boolean isAvailable() {
    try {
      return api.showModel(new OllamaApi.ShowModelRequest(model)) != null;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  @Override
  public Health health() {
    boolean available = isAvailable();
    return (available ? Health.up() : Health.down())
        .withDetail("model", model)
        .withDetail("initialization", available ? "available" : "initializing or unavailable")
        .build();
  }
}
