package com.garmin.analytics.garmin.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class GarminApiKeyAuthenticatorTest {

    @Test
    void authenticatesAndParsesBearerToken() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"access_token\":\"abc\",\"expires_in\":120}"));
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
            AuthToken token = authenticator.authenticate();

            assertEquals("abc", token.accessToken());
            assertEquals("/oauth/token", server.takeRequest().getPath());
        }
    }

    @Test
    void failsWhenTokenIsMissing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"foo\":\"bar\"}"));
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
            assertThrows(IllegalArgumentException.class, authenticator::authenticate);
        }
    }
}
