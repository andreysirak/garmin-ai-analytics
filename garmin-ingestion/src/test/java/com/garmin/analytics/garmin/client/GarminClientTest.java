package com.garmin.analytics.garmin.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class GarminClientTest {

    @Test
    void refreshesTokenAfterUnauthorizedResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"token-1\",\"expires_in\":120}"));
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"unauthorized\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"token-2\",\"expires_in\":120}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("[{\"calendarDate\":\"2026-11-05\",\"totalSteps\":4321}]"));
            server.start();

            GarminApiConfig config = new GarminApiConfig(
                    server.url("/").uri(),
                    server.url("/oauth/token").uri(),
                    "demo-api-key",
                    "x-api-key",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2),
                    1,
                    10
            );

            GarminApiKeyAuthenticator authenticator = new GarminApiKeyAuthenticator(HttpClient.newHttpClient(), config);
            CachingTokenProvider tokenProvider = new CachingTokenProvider(authenticator);
            GarminClient client = new GarminClient(HttpClient.newHttpClient(), tokenProvider, config);

            String body = client.get("/usersummary-service/stats/steps/daily/2026-11-05/2026-11-05", Map.of());
            assertEquals("[{\"calendarDate\":\"2026-11-05\",\"totalSteps\":4321}]", body);

            RecordedRequest firstToken = server.takeRequest();
            RecordedRequest firstApiCall = server.takeRequest();
            RecordedRequest secondToken = server.takeRequest();
            RecordedRequest secondApiCall = server.takeRequest();

            assertEquals("/oauth/token", firstToken.getPath());
            assertEquals("Bearer token-1", firstApiCall.getHeader("Authorization"));
            assertEquals("/oauth/token", secondToken.getPath());
            assertEquals("Bearer token-2", secondApiCall.getHeader("Authorization"));
        }
    }
}
