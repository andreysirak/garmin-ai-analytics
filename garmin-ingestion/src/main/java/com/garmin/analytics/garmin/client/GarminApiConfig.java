package com.garmin.analytics.garmin.client;

import java.net.URI;
import java.time.Duration;

public record GarminApiConfig(
        URI baseUri,
        URI tokenUri,
        String apiKey,
        String apiKeyHeader,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetries,
        long retryBackoffMillis
) {}
