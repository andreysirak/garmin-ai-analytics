package com.garmin.analytics.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("garmin.chat.execution")
public record ChatExecutionProperties(Duration timeout) {

  public ChatExecutionProperties {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Chat execution timeout must be positive");
    }
  }
}
