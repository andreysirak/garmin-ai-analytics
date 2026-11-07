package com.garmin.analytics.airflow;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.apache.airflow.sdk.Connection;

import com.garmin.analytics.garmin.client.GarminApiConfig;

public final class GarminConnectionSettings {

    private static final String DEFAULT_TOKEN_PATH = "/oauth/token";
    private static final String DEFAULT_API_KEY_HEADER = "x-api-key";
    private static final Path DEFAULT_RAW_BASE_PATH = Paths.get("/opt/airflow/data/raw/garmin");

    private final URI baseUri;
    private final URI tokenUri;
    private final String apiKey;
    private final String apiKeyHeader;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final long retryBackoffMillis;
    private final Path rawBasePath;

    private GarminConnectionSettings(
            URI baseUri,
            URI tokenUri,
            String apiKey,
            String apiKeyHeader,
            Duration connectTimeout,
            Duration requestTimeout,
            int maxRetries,
            long retryBackoffMillis,
            Path rawBasePath
    ) {
        this.baseUri = baseUri;
        this.tokenUri = tokenUri;
        this.apiKey = apiKey;
        this.apiKeyHeader = apiKeyHeader;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.retryBackoffMillis = retryBackoffMillis;
        this.rawBasePath = rawBasePath;
    }

    public static GarminConnectionSettings from(Connection connection) {
        Objects.requireNonNull(connection, "Airflow connection garmin_api is required");
        Map<String, Object> extra = asMap(connection.extra);

        String host = requireNonBlank(connection.host, "garmin_api.host is required");
        URI baseUri = URI.create(normalizeBaseUrl(host));

        String apiKey = firstNonBlank(
                asString(extra.get("api_key")),
                connection.password,
                connection.login
        );
        if (apiKey == null) {
            throw new IllegalArgumentException("Garmin API key is required in garmin_api (extra.api_key or password/login)");
        }

        String tokenUrl = asString(extra.get("token_url"));
        String tokenPath = asString(extra.getOrDefault("token_path", DEFAULT_TOKEN_PATH));
        URI tokenUri = tokenUrl != null ? URI.create(tokenUrl) : baseUri.resolve(tokenPath);

        String apiKeyHeader = firstNonBlank(asString(extra.get("token_api_key_header")), DEFAULT_API_KEY_HEADER);
        Duration connectTimeout = Duration.ofSeconds(asLong(extra.get("connect_timeout_seconds"), 10));
        Duration requestTimeout = Duration.ofSeconds(asLong(extra.get("request_timeout_seconds"), 30));
        int maxRetries = (int) asLong(extra.get("max_retries"), 2);
        long retryBackoffMillis = asLong(extra.get("retry_backoff_millis"), 500);

        String rawBasePath = asString(extra.get("raw_base_path"));
        Path resolvedRawPath = rawBasePath == null ? DEFAULT_RAW_BASE_PATH : Paths.get(rawBasePath);

        return new GarminConnectionSettings(
                baseUri,
                tokenUri,
                apiKey,
                apiKeyHeader,
                connectTimeout,
                requestTimeout,
                maxRetries,
                retryBackoffMillis,
                resolvedRawPath
        );
    }

    public GarminApiConfig toApiConfig() {
        return new GarminApiConfig(
                baseUri,
                tokenUri,
                apiKey,
                apiKeyHeader,
                connectTimeout,
                requestTimeout,
                maxRetries,
                retryBackoffMillis
        );
    }

    public Path rawBasePath() {
        return rawBasePath;
    }

    private static String normalizeBaseUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("garmin_api.extra must be a JSON object");
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String stringValue = value.toString().trim();
        return stringValue.isEmpty() ? null : stringValue;
    }

    private static long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
