package com.garmin.analytics.chat.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaModelHealthIndicatorTest {

  private final RestClient.Builder client = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
  private final OllamaModelHealthIndicator indicator = new OllamaModelHealthIndicator(
      OllamaApi.builder().baseUrl("http://ollama:11434").restClientBuilder(client).build(),
      "qwen2.5:1.5b-instruct-q4_K_M");

  @Test
  void reportsAvailableOnlyAfterTheConfiguredModelCanBeShown() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andExpect(content().json("{\"model\":\"qwen2.5:1.5b-instruct-q4_K_M\"}"))
        .andRespond(withSuccess("{\"modelfile\":\"FROM synthetic\"}", MediaType.APPLICATION_JSON));

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
  void treatsServerConnectionFailureAsUnavailable() {
    server.expect(requestTo("http://ollama:11434/api/show"))
        .andRespond(request -> { throw new IOException("synthetic connection failure"); });

    assertThat(indicator.isAvailable()).isFalse();
    server.verify();
  }
}
