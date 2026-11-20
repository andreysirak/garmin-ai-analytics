package dev.garmin.evaluation.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.report.Capture;
import dev.garmin.evaluation.report.PrivateFiles;
import dev.garmin.evaluation.transport.LoopbackHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationCliTest {
    @TempDir
    Path workspace;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach
    void createDataDirectory() throws Exception {
        Files.createDirectory(workspace.resolve("data"));
    }

    @Test
    void listValidatesAllScenariosOffline() throws Exception {
        assertEquals(0, cli("list"));
        assertEquals(23, output.toString(UTF_8).lines().count());
        assertFalse(Files.exists(run("unused").getParent()));
    }

    @Test
    void capturesPublicApplicationHttpAndVerifiesReviewAndRegressions() throws Exception {
        try (var stub = new Stub(request -> Response.ok(Json.object().put("answer", "A synthetic answer.")))) {
            var base = run("base");
            var next = run("next");
            assertEquals(2, application(stub, base));
            assertEquals(2, application(stub, next));
            assertEquals(12, stub.requests.size());
            var holdout = EvaluationSuite.load().select("holdout", "routing");
            for (int i = 0; i < stub.requests.size(); i++) {
                var request = stub.requests.get(i);
                assertEquals("/api/chat", request.path());
                assertEquals("POST", request.method());
                assertEquals(Json.object().put("message", holdout.get(i % 6 / 2).get("question").asText()), request.body());
            }

            var captured = Capture.read(base);
            assertEquals("synthetic-test", captured.manifest().get("deployment_id").asText());
            assertEquals(6, captured.rows().size());
            assertTrue(captured.rows().stream().allMatch(row -> row.get("status").asText().equals("NEEDS_REVIEW")));

            var raw = base.resolve("results.jsonl");
            String original = PrivateFiles.fileHash(raw);
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(base)));
            try (var files = Files.list(base)) {
                for (var file : files.toList()) {
                    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
                }
            }

            assertEquals(2, cli("compare", base.toString(), next.toString(), "--output", run("pending.json").toString()));
            assertEquals(6, PrivateFiles.read(run("pending.json")).get("inconclusive").size());

            var baselineReview = review(base, false);
            var candidateReview = review(next, true);
            assertEquals(0, cli("review", base.toString(), baselineReview.toString(),
                    "--output", base.resolve("reviewed.jsonl").toString()));
            assertEquals(2, cli("review", next.toString(), candidateReview.toString(),
                    "--output", next.resolve("reviewed.jsonl").toString()));
            assertEquals(original, PrivateFiles.fileHash(raw));
            assertThrows(IllegalArgumentException.class, () -> application(stub, base));
            assertEquals(12, stub.requests.size());
            assertThrows(IllegalArgumentException.class, () -> cli("review", base.toString(), baselineReview.toString(),
                    "--output", base.resolve("reviewed.jsonl").toString()));

            var comparison = run("comparison.json");
            assertEquals(2, compareReviewed(base, next, comparison));
            var report = PrivateFiles.read(comparison);
            assertEquals("application/HOLDOUT-001/1", report.at("/regressions/0").asText());
            assertEquals(5, report.get("unchanged").asInt());

            var edited = new ArrayList<>(PrivateFiles.readRows(next.resolve("reviewed.jsonl")));
            ((ObjectNode) edited.getFirst()).put("status", "PASS");
            PrivateFiles.saveRows(next.resolve("edited.jsonl"), edited);
            assertThrows(IllegalArgumentException.class,
                    () -> Capture.read(next).comparisonRows(next, "edited.jsonl"));
            Files.writeString(raw, "\n", java.nio.file.StandardOpenOption.APPEND);
            assertThrows(IllegalArgumentException.class, () -> Capture.read(base));
        }
    }

    @Test
    void blocksMissingAttestationAndSymbolicLinkOutputsBeforeHttp() throws Exception {
        try (var stub = new Stub(request -> Response.ok(Json.object()))) {
            assertThrows(IllegalArgumentException.class, () -> cli("run", "--mode", "application",
                    "--application-url", stub.origin(), "--output", run("missing-ack").toString()));
            assertThrows(IllegalArgumentException.class, () -> cli("run", "--mode", "application",
                    "--synthetic-database", "--application-url", stub.origin(), "--output", run("missing-id").toString()));
            assertFalse(Files.exists(run("unused").getParent()));
            Files.createDirectory(run("unused").getParent());
            Files.createSymbolicLink(run("link"), workspace);
            assertThrows(IllegalArgumentException.class, () -> application(stub, run("link/escape")));
            assertThrows(IllegalArgumentException.class, () -> application(stub, workspace.resolve("outside")));
            assertTrue(stub.requests.isEmpty());
        }
    }

    @Test
    void exercisesModelRoutingAndOllamaProvenanceThroughProviderApi() throws Exception {
        var responses = new java.util.ArrayDeque<JsonNode>();
        for (var scenario : EvaluationSuite.load().select("holdout", "routing")) {
            responses.add(completion(Json.encode(scenario.get("expected"))));
        }
        try (var stub = ollama(request -> Response.ok(responses.removeFirst()), false)) {
            var run = run("model");
            assertEquals(0, cli("run", "--model", "fixture", "--ollama-url", stub.origin(), "--repetitions", "1",
                    "--split", "holdout", "--temperature", "0.25", "--num-ctx", "4096", "--num-predict", "128",
                    "--output", run.toString()));
            assertEquals(List.of("/api/version", "/api/tags", "/api/show", "/api/chat", "/api/chat", "/api/chat", "/api/tags"),
                    stub.requests.stream().map(Request::path).toList());
            assertEquals(Json.object().put("model", "fixture"), stub.requests.get(2).body());
            var capture = Capture.read(run);
            assertEquals("ollama", capture.manifest().get("provider").asText());
            assertEquals("sha256:installed", capture.manifest().at("/model/digest").asText());
            assertEquals("test-1", capture.manifest().at("/runtime/version").asText());
            assertEquals("synthetic", PrivateFiles.read(run.resolve("model-info.json")).get("family").asText());
            assertTrue(capture.completion().get("model_unchanged").asBoolean());
            assertEquals(3, capture.comparisonRows(run, Capture.RESULTS).size());
            var payload = stub.requests.get(3).body();
            assertEquals("fixture", payload.get("model").asText());
            assertFalse(payload.get("stream").asBoolean());
            assertEquals(3, payload.at("/format/anyOf").size());
            assertEquals(4096, payload.at("/options/num_ctx").asInt());
            assertEquals(128, payload.at("/options/num_predict").asInt());
            assertEquals(0.25, payload.at("/options/temperature").asDouble());
            assertEquals(20, capture.rows().getFirst().at("/response/eval_count").asInt());
        }
    }

    @Test
    void recordsModelErrorsPendingReviewAndIdentityReplacement() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var stub = ollama(request -> switch (calls.getAndIncrement()) {
            case 0 -> Response.ok(completion("3000 steps each day"));
            case 1 -> Response.ok(completion("truncated").put("done_reason", "length"));
            case 2 -> Response.ok(completion("tools called").set("message",
                    Json.object().putArray("tool_calls").addObject().put("name", "unexpected")));
            case 3 -> Response.ok(Json.object().put("done", true));
            case 4 -> new Response(503, "{\"error\":\"private provider detail\"}");
            case 5 -> new Response(200, "invalid JSON");
            default -> Response.ok(completion("No supported conclusion."));
        }, true)) {
            var run = run("explanation");
            assertEquals(2, cli("run", "--model", "fixture", "--ollama-url", stub.origin(), "--repetitions", "1",
                    "--stage", "explanation", "--output", run.toString()));
            assertEquals(12, calls.get());
            var capture = Capture.read(run);
            var first = capture.rows().getFirst();
            assertEquals("NEEDS_REVIEW", first.get("status").asText());
            assertEquals("3000", first.at("/numeric_hints/0").asText());
            assertEquals(3, first.at("/evidence/metrics/0/coverage/daysWithData").asInt());
            assertTrue(first.at("/input/format").isMissingNode());
            assertEquals(List.of("FAIL", "FAIL", "ERROR", "ERROR", "ERROR"), capture.rows().subList(1, 6)
                    .stream().map(row -> row.get("status").asText()).toList());
            assertEquals("InvalidModelResponseException", capture.rows().get(3).get("error_type").asText());
            assertEquals("HttpStatusException", capture.rows().get(4).get("error_type").asText());
            assertEquals("InvalidResponseException", capture.rows().get(5).get("error_type").asText());
            assertFalse(capture.completion().get("model_unchanged").asBoolean());
            assertThrows(IllegalArgumentException.class, () -> capture.comparisonRows(run, Capture.RESULTS));
            assertFalse(output.toString(UTF_8).contains("private provider detail"));
            var summary = PrivateFiles.read(run.resolve("summary.json")).get("explanation");
            assertEquals(3, summary.at("/counts/ERROR").asInt());
            assertTrue(summary.has("p95_seconds"));
        }
    }

    @Test
    void refusesMissingModelBeforeInference() throws Exception {
        try (var stub = new Stub(request -> request.path().equals("/api/version")
                ? Response.ok(Json.object().put("version", "test"))
                : Response.ok(Json.object().set("models", Json.MAPPER.createArrayNode())))) {
            var run = run("missing-model");
            assertThrows(IOException.class, () -> cli("run", "--ollama-url", stub.origin(), "--output", run.toString()));
            assertEquals(List.of("/api/version", "/api/tags"), stub.requests.stream().map(Request::path).toList());
            assertFalse(Files.exists(run));
        }
    }

    @Test
    void limitsTransportAndRejectsRedirectsCredentialsAndExternalHosts() throws Exception {
        for (String url : List.of("http://example.com", "https://localhost", "http://u:p@localhost",
                "http://localhost/path", "http://localhost/?a=1", "http://localhost/#test", "http://localhost:0")) {
            assertThrows(IllegalArgumentException.class, () -> new LoopbackHttp(url, Duration.ofSeconds(1)));
        }
        try (var stub = new Stub(request -> switch (request.path()) {
            case "/redirect" -> new Response(302, "", "/target");
            case "/large" -> new Response(200, "a".repeat(8 * 1024 * 1024 + 1));
            case "/slow" -> new Response(200, "{}", null, true);
            default -> Response.ok(Json.object());
        });
             var http = new LoopbackHttp(stub.origin(), Duration.ofSeconds(5))) {
            var redirect = assertThrows(LoopbackHttp.HttpStatusException.class, () -> http.exchange("/redirect", null));
            assertEquals(302, redirect.statusCode());
            assertThrows(LoopbackHttp.InvalidResponseException.class, () -> http.exchange("/large", null));
            try (var brief = new LoopbackHttp(stub.origin(), Duration.ofMillis(250))) {
                assertThrows(HttpTimeoutException.class, () -> brief.exchange("/slow", null));
            }
            assertFalse(stub.requests.stream().anyMatch(request -> request.path().equals("/target")));
        }
    }

    private Stub ollama(Function<Request, Response> chat, boolean replaceModel) throws IOException {
        var tags = new java.util.concurrent.atomic.AtomicInteger();
        return new Stub(request -> switch (request.path()) {
            case "/api/version" -> Response.ok(Json.object().put("version", "test-1"));
            case "/api/tags" -> {
                var response = Json.object();
                response.putArray("models").addObject().put("model", "fixture")
                        .put("digest", tags.getAndIncrement() > 0 && replaceModel ? "sha256:replaced" : "sha256:installed");
                yield Response.ok(response);
            }
            case "/api/show" -> Response.ok(Json.object().put("family", "synthetic"));
            case "/api/chat" -> chat.apply(request);
            default -> new Response(404, "{}");
        });
    }

    private static ObjectNode completion(String content) {
        var response = Json.object().put("done", true).put("done_reason", "stop").put("eval_count", 20);
        response.putObject("message").put("content", content);
        return response;
    }

    private Path review(Path run, boolean failFirst) throws Exception {
        var annotations = PrivateFiles.read(run.resolve("review-template.json"));
        boolean fail = failFirst;
        for (var entry : annotations) {
            var annotation = (ObjectNode) entry;
            annotation.put("reviewer", "test-reviewer").put("rationale", "Synthetic review fixture");
            var checks = (ObjectNode) annotation.get("checks");
            checks.properties().stream().map(java.util.Map.Entry::getKey).toList()
                    .forEach(key -> checks.put(key, "PASS"));
            if (fail) {
                checks.put("factual_accuracy", "FAIL");
                fail = false;
            }
        }
        var result = run.resolve("annotations.json");
        PrivateFiles.save(result, annotations);
        return result;
    }

    private int compareReviewed(Path base, Path next, Path target) throws Exception {
        return cli("compare", base.toString(), next.toString(), "--baseline-results", "reviewed.jsonl",
                "--candidate-results", "reviewed.jsonl", "--output", target.toString());
    }

    private int application(Stub stub, Path run) throws Exception {
        return cli("run", "--mode", "application", "--synthetic-database", "--deployment-id", "synthetic-test",
                "--application-url", stub.origin(), "--split", "holdout", "--repetitions", "2", "--output", run.toString());
    }

    private int cli(String... args) throws Exception {
        return EvaluationCli.execute(args, workspace, new PrintStream(output, true, UTF_8));
    }

    private Path run(String name) {
        return workspace.resolve("data/evaluations").resolve(name);
    }

    private record Request(String path, String method, JsonNode body) {}

    private record Response(int status, String body, String redirect, boolean stallBody) {
        Response(int status, String body) { this(status, body, null); }
        Response(int status, String body, String redirect) { this(status, body, redirect, false); }
        static Response ok(JsonNode value) { return new Response(200, Json.encode(value)); }
    }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;
        private final Function<Request, Response> respond;
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        Stub(Function<Request, Response> respond) throws IOException {
            this.respond = respond;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                var body = exchange.getRequestBody().readAllBytes();
                var request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                        body.length == 0 ? null : Json.MAPPER.readTree(body));
                requests.add(request);
                var response = respond.apply(request);
                if (response.redirect() != null) {
                    exchange.getResponseHeaders().set("Location", response.redirect());
                }
                var bytes = response.body().getBytes(UTF_8);
                exchange.sendResponseHeaders(response.status(), bytes.length);
                if (response.stallBody()) {
                    try {
                        Thread.sleep(750);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
