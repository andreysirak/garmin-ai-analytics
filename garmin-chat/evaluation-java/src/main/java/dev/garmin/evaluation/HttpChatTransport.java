package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HttpChatTransport implements ChatTransport {
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    public HttpChatTransport(URI baseUrl, Duration timeout) {
        if (!"http".equals(baseUrl.getScheme()) || baseUrl.getHost() == null ||
                !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("Only loopback HTTP endpoints are allowed");
        }
        if (!baseUrl.getPath().isEmpty() && !"/".equals(baseUrl.getPath())) {
            throw new IllegalArgumentException("Provide the application base URL, without a path");
        }
        this.endpoint = baseUrl.resolve("/api/chat");
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public JsonNode send(JsonNode request) throws IOException, InterruptedException {
        HttpRequest message = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.mapper().writeValueAsString(request)))
                .build();
        HttpResponse<String> response = client.send(message, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Unexpected HTTP status: " + response.statusCode());
        if (response.body().length() > 2_000_000) throw new IOException("Response exceeds maximum size");
        return JsonSupport.mapper().readTree(response.body());
    }
}