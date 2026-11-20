package dev.garmin.evaluation.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LoopbackHttp implements JsonHttp, AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final HttpClient client;
    private final URI origin;
    private final Duration timeout;

    public LoopbackHttp(String endpoint, Duration timeout) {
        origin = URI.create(endpoint);
        String host = origin.getHost();
        if (!"http".equalsIgnoreCase(origin.getScheme()) || host == null
                || !Set.of("127.0.0.1", "localhost", "[::1]").contains(host.toLowerCase(Locale.ROOT))
                || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                || origin.getRawFragment() != null || !Set.of("", "/").contains(origin.getRawPath())
                || origin.getPort() == 0 || origin.getPort() > 65535) {
            throw new IllegalArgumentException("Expected a bare HTTP loopback origin without credentials");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(600)) > 0) {
            throw new IllegalArgumentException("HTTP timeout must be greater than 0 and at most 600 seconds");
        }
        this.timeout = timeout;
        client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    @Override
    public JsonNode exchange(String path, JsonNode payload) throws IOException, InterruptedException {
        if (!path.matches("/[a-z/]+")) {
            throw new IllegalArgumentException("Expected a fixed local API path");
        }
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json");
        if (payload != null) {
            request.POST(HttpRequest.BodyPublishers.ofString(Json.encode(payload)));
        }
        var pending = client.sendAsync(request.build(), info -> {
            if (info.statusCode() < 200 || info.statusCode() >= 300) {
                throw new java.io.UncheckedIOException(new HttpStatusException(info.statusCode()));
            }
            return new BoundedBody();
        });
        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("Evaluation HTTP request timed out");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof java.io.UncheckedIOException failure) {
                throw failure.getCause();
            }
            if (e.getCause() instanceof IOException failure) {
                throw failure;
            }
            throw new IOException("HTTP exchange failed", e.getCause());
        }
        try {
            var value = Json.MAPPER.readTree(response.body());
            if (value == null || !value.isObject()) {
                throw new InvalidResponseException("Expected an HTTP JSON object");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new InvalidResponseException("Invalid HTTP JSON response");
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        HttpStatusException(int statusCode) {
            super("HTTP status " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class InvalidResponseException extends IOException {
        InvalidResponseException(String message) {
            super(message);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long remaining = MAX_BYTES;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            remaining -= buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (remaining < 0) {
                subscription.cancel();
                onError(new InvalidResponseException("HTTP response exceeds evaluation size limit"));
            } else {
                body.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) {
            body.onError(error);
        }

        @Override
        public void onComplete() {
            body.onComplete();
        }
    }
}
