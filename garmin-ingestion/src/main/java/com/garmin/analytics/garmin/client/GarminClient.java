package com.garmin.analytics.garmin.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class GarminClient implements GarminHttpClient {

    private final HttpClient httpClient;
    private final TokenProvider tokenProvider;
    private final GarminApiConfig config;

    public GarminClient(HttpClient httpClient, TokenProvider tokenProvider, GarminApiConfig config) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider is required");
        this.config = Objects.requireNonNull(config, "config is required");
    }

    @Override
    public String get(String path, Map<String, String> queryParams) throws IOException, InterruptedException {
        URI uri = buildUri(path, queryParams);
        int attempts = 0;
        boolean tokenRefreshed = false;

        while (true) {
            attempts++;
            try {
                String token = tokenProvider.getAccessToken();
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(config.requestTimeout())
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 401 && !tokenRefreshed) {
                    tokenProvider.invalidate();
                    tokenRefreshed = true;
                    continue;
                }

                if (status >= 200 && status < 300) {
                    return response.body();
                }

                if (isRetriableStatus(status) && attempts <= config.maxRetries() + 1) {
                    sleepBackoff(attempts - 1);
                    continue;
                }

                throw new IOException("Garmin API GET failed for " + uri + " with HTTP " + status);
            } catch (IOException ioException) {
                if (attempts > config.maxRetries() + 1) {
                    throw ioException;
                }
                sleepBackoff(attempts - 1);
            }
        }
    }

    private URI buildUri(String path, Map<String, String> queryParams) {
        String base = config.baseUri().toString().replaceAll("/+$", "");
        String suffix = path.startsWith("/") ? path : "/" + path;
        StringBuilder uriBuilder = new StringBuilder(base).append(suffix);

        if (queryParams != null && !queryParams.isEmpty()) {
            uriBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) {
                    uriBuilder.append("&");
                }
                first = false;
                uriBuilder.append(encode(entry.getKey()))
                        .append("=")
                        .append(encode(entry.getValue()));
            }
        }

        return URI.create(uriBuilder.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isRetriableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private void sleepBackoff(int retryIndex) throws InterruptedException {
        long delay = Math.max(1L, config.retryBackoffMillis()) * (1L << Math.min(retryIndex, 6));
        Thread.sleep(delay);
    }
}
