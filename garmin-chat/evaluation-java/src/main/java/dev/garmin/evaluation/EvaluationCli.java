package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class EvaluationCli {
    private EvaluationCli() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected list or application");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Invalid argument");
            options.put(args[i].substring(2), args[i + 1]);
        }
        Path casesPath = Path.of(options.getOrDefault("cases", "garmin-chat/evaluation/cases.json"));
        EvaluationSuite suite = EvaluationSuite.load(casesPath);
        if ("list".equals(args[0])) {
            for (JsonNode c : suite.document().path("cases")) {
                System.out.println(c.path("id").asText() + " " + c.path("stage").asText()
                        + " " + c.path("split").asText() + " " + c.path("question").asText());
            }
            return;
        }
        if (!"application".equals(args[0])) throw new IllegalArgumentException("Unsupported command: " + args[0]);
        if (!"yes".equals(options.get("synthetic-database")) ||
                options.getOrDefault("deployment-id", "").isBlank()) {
            throw new IllegalArgumentException("Isolated synthetic deployment attestation required");
        }
        Path output = Path.of(options.getOrDefault("output", "")).toAbsolutePath().normalize();
        Path root = Path.of("data/evaluations").toAbsolutePath().normalize();
        if (!output.startsWith(root) || output.equals(root)) throw new IllegalArgumentException("Output must be inside data/evaluations");
        String split = options.getOrDefault("split", "regression");
        if (!java.util.Set.of("all", "regression", "holdout").contains(split)) throw new IllegalArgumentException("Invalid split");
        int repetitions = Integer.parseInt(options.getOrDefault("repetitions", "3"));
        if (repetitions < 1 || repetitions > 20) throw new IllegalArgumentException("Repetitions must be 1..20");
        ChatTransport transport = new HttpChatTransport(
                URI.create(options.getOrDefault("application-url", "http://127.0.0.1:8081")),
                Duration.ofSeconds(Long.parseLong(options.getOrDefault("timeout", "120"))));
        Files.createDirectories(root);
        Files.createDirectory(output);
        ApplicationEvaluator evaluator = new ApplicationEvaluator();
        int pending = 0;
        for (JsonNode c : suite.document().path("cases")) {
            if (!"routing".equals(c.path("stage").asText()) ||
                    (!"all".equals(split) && !split.equals(c.path("split").asText()))) continue;
            for (int trial = 1; trial <= repetitions; trial++) {
                ObjectNode request = JsonSupport.mapper().createObjectNode().put("message", c.path("question").asText());
                ObjectNode row = JsonSupport.mapper().createObjectNode()
                        .put("case_id", c.path("id").asText()).put("stage", "application")
                        .put("trial", trial).put("case_sha256", JsonSupport.hash(c))
                        .put("input_sha256", JsonSupport.hash(request))
                        .put("started_at", Instant.now().toString());
                row.set("input", request);
                long start = System.nanoTime();
                try {
                    JsonNode response = transport.send(request);
                    row.set("response", response);
                    row.put("output_sha256", JsonSupport.hash(response));
                    ApplicationEvaluator.Verdict verdict = evaluator.evaluate(response);
                    row.put("status", verdict.status());
                    row.set("failures", JsonSupport.mapper().valueToTree(verdict.failures()));
                    row.set("review_required", JsonSupport.mapper().valueToTree(verdict.reviewRequired()));
                    if (!"PASS".equals(verdict.status())) pending++;
                } catch (Exception error) {
                    row.put("status", "ERROR").put("error_type", error.getClass().getSimpleName());
                    row.set("failures", JsonSupport.mapper().createArrayNode());
                    row.set("review_required", JsonSupport.mapper().createArrayNode());
                    pending++;
                }
                row.put("elapsed_seconds", (System.nanoTime() - start) / 1_000_000_000.0);
                Files.writeString(output.resolve("results.jsonl"), JsonSupport.mapper().writeValueAsString(row) + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.out.println(row.path("case_id").asText() + ": " + row.path("status").asText());
            }
        }
        ObjectNode summary = JsonSupport.mapper().createObjectNode()
                .put("completed_at", Instant.now().toString()).put("pending_or_failed", pending)
                .put("deployment_id", options.get("deployment-id"));
        JsonSupport.writeNew(output.resolve("summary.json"), summary);
        if (pending > 0) System.exit(2);
    }
}