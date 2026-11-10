package com.garmin.analytics.integration.common.client;

import com.garmin.analytics.integration.common.exception.GarminApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Performs a single authenticated Garmin API request. */
public final class GarminClient {

  private static final URI BASE_URI = URI.create("https://personal.connectapi.garmin.com/");
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final String bearerToken;

  public GarminClient(HttpClient httpClient, String bearerToken) {
    this.httpClient = httpClient;
    this.bearerToken = bearerToken;
  }

  public String callGet(String endpoint, Map<String, String> queryParameters)
      throws GarminApiException, InterruptedException {
    String query = queryParameters.entrySet().stream()
        .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
        .collect(Collectors.joining("&"));
    URI uri = BASE_URI.resolve(endpoint + (query.isEmpty() ? "" : "?" + query));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new GarminApiException("Garmin API transport failure", exception);
    }
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new GarminApiException("Garmin API request failed with HTTP " + status);
    }
    return response.body();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
