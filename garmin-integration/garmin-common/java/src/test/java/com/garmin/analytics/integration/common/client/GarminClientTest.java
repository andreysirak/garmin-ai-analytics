package com.garmin.analytics.integration.common.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.garmin.analytics.integration.common.exception.GarminApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GarminClientTest {

  private HttpClient httpClient;
  private HttpResponse<String> response;
  private GarminClient client;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    httpClient = mock(HttpClient.class);
    response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(httpClient.<String>send(any(), any())).thenReturn(response);
    client = new GarminClient(httpClient, "synthetic-token");
  }

  @Test
  void usesTheFixedBaseUriAndEncodesQueryParametersWithoutChangingTheBody() throws Exception {
    String rawJson = " \r\n{\"unknown\":null,\"number\":1.00,\"note\":\"café\"}\n";
    when(response.body()).thenReturn(rawJson);

    String actual = client.callGet("/caller/resource", Map.of("requested date", "café /+?"));

    assertEquals(rawJson, actual);
    HttpRequest request = sentRequest();
    assertEquals(URI.create("https://personal.connectapi.garmin.com/caller/resource"
        + "?requested+date=caf%C3%A9+%2F%2B%3F"), request.uri());
    assertEquals("GET", request.method());
    assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
    assertEquals(Optional.of("Bearer synthetic-token"),
        request.headers().firstValue("Authorization"));
    assertEquals(Optional.of("application/json"), request.headers().firstValue("Accept"));
  }

  @Test
  void omitsTheQuerySeparatorForAnEmptyParameterMap() throws Exception {
    when(response.body()).thenReturn("{}");

    assertEquals("{}", client.callGet("/empty", Map.of()));

    assertEquals(URI.create("https://personal.connectapi.garmin.com/empty"), sentRequest().uri());
  }

  @Test
  void propagatesHttpFailureWithoutRetryingOrReadingTheErrorBody() throws Exception {
    when(response.statusCode()).thenReturn(401);
    when(response.body()).thenReturn("private-api-body");

    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> client.callGet("/caller/resource", Map.of()));

    assertTrue(failure.getMessage().contains("HTTP 401"));
    assertFalse(failure.getMessage().contains("private-api-body"));
    verify(httpClient).send(any(), any());
    verify(response, never()).body();
  }

  @Test
  void preservesTransportFailureCause() throws Exception {
    IOException transportFailure = new IOException("synthetic transport failure");
    when(httpClient.<String>send(any(), any())).thenThrow(transportFailure);

    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> client.callGet("/caller/resource", Map.of()));

    assertSame(transportFailure, failure.getCause());
    verify(httpClient).send(any(), any());
  }

  @Test
  void propagatesInterruptionWithoutConvertingItToApiFailure() throws Exception {
    InterruptedException interrupted = new InterruptedException("synthetic interruption");
    when(httpClient.<String>send(any(), any())).thenThrow(interrupted);

    assertSame(interrupted, assertThrows(InterruptedException.class,
        () -> client.callGet("/caller/resource", Map.of())));
    verify(httpClient).send(any(), any());
  }

  private HttpRequest sentRequest() throws Exception {
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(request.capture(), any());
    return request.getValue();
  }
}
