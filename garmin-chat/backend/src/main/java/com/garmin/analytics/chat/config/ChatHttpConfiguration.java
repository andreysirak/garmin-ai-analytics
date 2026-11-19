package com.garmin.analytics.chat.config;

import org.springframework.boot.autoconfigure.http.client.HttpClientProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChatHttpConfiguration {

  @Bean
  JdkClientHttpRequestFactoryBuilder chatHttpRequestFactoryBuilder(
      HttpClientProperties properties) {
    var readTimeout = properties.getReadTimeout();
    if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
      throw new IllegalArgumentException("Ollama HTTP read timeout must be positive");
    }
    // Spring's JDK request factory requests cancellation of the HTTP exchange on interruption.
    return ClientHttpRequestFactoryBuilder.jdk();
  }
}
