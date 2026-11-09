package com.garmin.analytics.integration.common.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.garmin.analytics.integration.common.exception.GarminApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GarminClientTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void sendsBearerTokenAndEncodedQueryAndReturnsOriginalBody() throws Exception {
    String rawJson = "{\"message\":\"raw response\",\"unknownField\":true}";
    server.enqueue(new MockResponse().setBody(rawJson));
    GarminClient client =
        new GarminClient(HttpClient.newHttpClient(), server.url("/").uri(), "synthetic-token");

    String response = client.callWithQueryParameters("/sleep/daily", Map.of("calendarDate", "2026-11-07"));

    assertEquals(rawJson, response);
    RecordedRequest request = server.takeRequest();
    assertEquals("/sleep/daily?calendarDate=2026-11-07", request.getPath());
    assertEquals("Bearer synthetic-token", request.getHeader("Authorization"));
    assertEquals("application/json", request.getHeader("Accept"));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void propagatesHttpErrorWithoutRetrying() {
    server.enqueue(new MockResponse().setResponseCode(401));
    GarminClient client =
        new GarminClient(HttpClient.newHttpClient(), server.url("/").uri(), "synthetic-token");

    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> client.callWithQueryParameters("/endpoint", Map.of()));
    assertTrue(failure.getMessage().contains("HTTP 401"));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void rejectsInvalidBaseUriAndEndpoint() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GarminClient(
                HttpClient.newHttpClient(), URI.create("https://user:pass@example.com"), "token"));
    GarminClient client =
        new GarminClient(HttpClient.newHttpClient(), server.url("/").uri(), "token");
    assertThrows(IllegalArgumentException.class, () -> client.callWithQueryParameters("relative", Map.of()));
  }

  @Test
  void preservesTransportFailureCause() throws Exception {
    java.net.URI unavailable = server.url("/").uri();
    server.shutdown();
    GarminClient client = new GarminClient(HttpClient.newHttpClient(), unavailable, "token");
    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> client.callWithQueryParameters("/endpoint", Map.of()));
    assertInstanceOf(IOException.class, failure.getCause());
  }

  @Test
  void propagatesInterruptionWithoutConvertingItToApiFailure() {
    GarminClient client = new GarminClient(HttpClient.newHttpClient(), server.url("/").uri(),
        "token");
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> client.callWithQueryParameters("/endpoint", Map.of()));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void rejectsEmptyBearerToken() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GarminClient(HttpClient.newHttpClient(), server.url("/").uri(), " "));
  }
}
