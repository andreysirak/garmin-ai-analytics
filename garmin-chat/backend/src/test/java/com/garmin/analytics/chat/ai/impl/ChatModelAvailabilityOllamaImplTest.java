package com.garmin.analytics.chat.ai.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ChatModelAvailabilityOllamaImplTest {

  private final RestClient.Builder client = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
  private final ChatModelAvailabilityOllamaImpl indicator = new ChatModelAvailabilityOllamaImpl(
      OllamaApi.builder().baseUrl("http://ollama:11434").restClientBuilder(client).build(),
      "qwen2.5:1.5b-instruct-q4_K_M");

  @Test
  void reportsAvailableOnlyAfterTheConfiguredModelCanBeShown() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andExpect(content().json("{\"model\":\"qwen2.5:1.5b-instruct-q4_K_M\"}"))
        .andRespond(withSuccess("{\"capabilities\":[\"completion\",\"tools\"]}",
            MediaType.APPLICATION_JSON));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    server.verify();
  }

  @Test
  void exposesInitializationStatusWhenTheModelHasNotBeenPulled() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("initialization", "initializing or unavailable");
    server.verify();
  }

  @Test
  void flagsModelsWithoutToolCapability() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andRespond(withSuccess("{\"capabilities\":[\"completion\"]}", MediaType.APPLICATION_JSON));

    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("initialization", "tool calling capability not reported");
    server.verify();
  }

  @Test
  void treatsServerConnectionFailureAsUnavailable() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andRespond(request -> { throw new IOException("synthetic connection failure"); });

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    server.verify();
  }
  @Test
  void preservesTimeoutClassificationDuringTheCapabilityCheckForChat() {
    var timeout = new HttpTimeoutException("synthetic timeout");
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andRespond(request -> { throw timeout; });

    assertThatThrownBy(indicator::requireAvailable)
        .isInstanceOf(ChatRequestTimeoutException.class).hasRootCause(timeout);
    server.verify();
  }

}
