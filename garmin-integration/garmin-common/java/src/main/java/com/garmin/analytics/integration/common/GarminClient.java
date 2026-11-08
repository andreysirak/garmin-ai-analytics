package com.garmin.analytics.integration.common;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Performs a single authenticated Garmin API request. */
public final class GarminClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final URI baseUri;
  private final String bearerToken;

  public GarminClient(HttpClient httpClient, URI baseUri, String bearerToken) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
    this.baseUri = validateBaseUri(baseUri);
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE must contain a bearer token");
    }
    this.bearerToken = bearerToken.trim();
  }

  public String get(String endpoint, Map<String, String> queryParameters)
      throws IOException, InterruptedException {
    URI uri = buildUri(endpoint, queryParameters);
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearerToken)
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new IOException(
          "Garmin API request to " + uri.getPath() + " failed with HTTP " + status);
    }
    return response.body();
  }

  private static URI validateBaseUri(URI baseUri) {
    Objects.requireNonNull(baseUri, "API base URL is required");
    if (!("http".equalsIgnoreCase(baseUri.getScheme())
            || "https".equalsIgnoreCase(baseUri.getScheme()))
        || baseUri.getHost() == null
        || baseUri.getUserInfo() != null
        || baseUri.getRawQuery() != null
        || baseUri.getFragment() != null) {
      throw new IllegalArgumentException("--api-base-url must be a valid HTTP(S) URL");
    }
    return baseUri;
  }

  private URI buildUri(String endpoint, Map<String, String> queryParameters) {
    if (endpoint == null || !endpoint.startsWith("/") || endpoint.startsWith("//")) {
      throw new IllegalArgumentException("Garmin endpoint must be an absolute path");
    }
    String base = baseUri.toString().replaceAll("/+$", "");
    StringBuilder result = new StringBuilder(base).append(endpoint);
    if (queryParameters != null && !queryParameters.isEmpty()) {
      boolean first = true;
      for (Map.Entry<String, String> entry : queryParameters.entrySet()) {
        result.append(first ? '?' : '&');
        first = false;
        result
            .append(encode(entry.getKey()))
            .append('=')
            .append(encode(entry.getValue()));
      }
    }
    return URI.create(result.toString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
