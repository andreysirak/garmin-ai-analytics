package com.garmin.analytics.garmin.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class GarminApiKeyAuthenticator implements GarminAuthenticator {

    private final HttpClient httpClient;
    private final GarminApiConfig config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GarminApiKeyAuthenticator(HttpClient httpClient, GarminApiConfig config) {
        this(httpClient, config, new ObjectMapper(), Clock.systemUTC());
    }

    GarminApiKeyAuthenticator(HttpClient httpClient, GarminApiConfig config, ObjectMapper objectMapper, Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.config = Objects.requireNonNull(config, "config is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public AuthToken authenticate() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(config.tokenUri())
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(config.apiKeyHeader(), config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Garmin token request failed with HTTP " + status);
        }

        JsonNode node = objectMapper.readTree(response.body());
        String token = extractToken(node);
        long expiresIn = node.path("expires_in").asLong(3600);
        Instant expiresAt = Instant.now(clock).plusSeconds(Math.max(expiresIn, 60));
        return new AuthToken(token, expiresAt);
    }

    private static String extractToken(JsonNode node) {
        String token = firstText(node, "access_token", "token");
        if (token == null) {
            throw new IllegalArgumentException("Token response does not contain access_token");
        }
        return token;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
