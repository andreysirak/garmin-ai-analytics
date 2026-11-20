package dev.garmin.evaluation.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.core.TrialRunner;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.dataset.EvidenceOracle;
import dev.garmin.evaluation.dataset.PromptFixtures;
import dev.garmin.evaluation.evaluator.ResponseEvaluator;
import dev.garmin.evaluation.evaluator.RoutingEvaluator;
import dev.garmin.evaluation.model.ModelProvider;
import dev.garmin.evaluation.model.OllamaProvider;
import dev.garmin.evaluation.report.Capture;
import dev.garmin.evaluation.report.PrivateFiles;
import dev.garmin.evaluation.report.Reports;
import dev.garmin.evaluation.review.HumanReview;
import dev.garmin.evaluation.transport.ChatHttpAdapter;
import dev.garmin.evaluation.transport.LoopbackHttp;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;

final class RunCommand {
    private record Prepared(JsonNode scenario, String stage, JsonNode request, TrialRunner.Grader grader) {}

    private RunCommand() {}

    static int execute(EvaluationCli.Options options, Path workspace, PrintStream out)
            throws IOException, InterruptedException {
        var suite = EvaluationSuite.load();
        String mode = options.choice("mode", "model", "model", "application");
        boolean application = mode.equals("application");
        if (application && !options.has("synthetic-database")) {
            throw new IllegalArgumentException("Application mode requires --synthetic-database and --deployment-id");
        }
        String deployment = application ? options.required("deployment-id") : null;
        String split = options.choice("split", "regression", "regression", "holdout", "all");
        String stage = options.choice("stage", "all", "routing", "explanation", "all");
        int repetitions = options.integer("repetitions", 3, 1, 20);
        var timeout = options.decimal("timeout", "120", BigDecimal.ZERO, new BigDecimal("600"));
        if (timeout.signum() == 0) {
            throw new IllegalArgumentException("HTTP timeout must be positive");
        }
        var duration = Duration.ofNanos(timeout.movePointRight(9).longValue());
        var cases = suite.select(split, application ? "routing" : stage);
        var files = new PrivateFiles(workspace);
        var output = Path.of(options.required("output"));
        files.destination(output);
        var plan = Json.object().put("suite_version", suite.version()).put("rubric_version", suite.rubricVersion())
                .put("split", split).put("stage", application ? "application" : stage)
                .put("mode", mode).put("repetitions", repetitions)
                .put("grader_sha256", fingerprint(ResponseEvaluator.class, RoutingEvaluator.class, EvidenceOracle.class));
        plan.set("cases", Json.MAPPER.valueToTree(cases));
        plan.set("protocol", suite.protocol());
        var manifest = Json.object().put("format_version", Capture.FORMAT_VERSION)
                .put("started_at", Instant.now().toString()).put("protocol_sha256", Json.hash(plan))
                .put("runner_sha256", runnerFingerprint())
                .put("java_version", Runtime.version().toString()).put("timeout_seconds", timeout);
        manifest.set("plan", plan);
        addSourceProvenance(workspace, manifest);

        if (application) {
            manifest.put("deployment_id", deployment)
                    .put("measurement", "Public application HTTP wall time");
            try (var http = new LoopbackHttp(options.get("application-url", "http://127.0.0.1:8081"), duration)) {
                var adapter = new ChatHttpAdapter(http);
                var trials = cases.stream().map(c -> new Prepared(c, "application",
                        adapter.request(Json.text(c, "question")), ResponseEvaluator::application)).toList();
                return capture(files, output, manifest, trials, adapter, repetitions, null, null, out);
            }
        }

        var prompts = PromptFixtures.load();
        var settings = Json.object()
                .put("temperature", options.decimal("temperature", "0", BigDecimal.ZERO, new BigDecimal("2")))
                .put("num_ctx", options.integer("num-ctx", suite.protocol().get("context_tokens").intValue(), 1, Integer.MAX_VALUE))
                .put("num_predict", options.integer("num-predict", suite.protocol().get("response_tokens").intValue(), 1, Integer.MAX_VALUE))
                .put("num_gpu", options.integer("num-gpu", 0, 0, Integer.MAX_VALUE));
        try (var http = new LoopbackHttp(options.get("ollama-url", "http://127.0.0.1:11434"), duration)) {
            ModelProvider provider = new OllamaProvider(http, options.get("model", "qwen2.5:1.5b-instruct-q4_K_M"));
            var routing = new RoutingEvaluator(suite.protocol());
            var trials = cases.stream().map(c -> new Prepared(c, Json.text(c, "stage"),
                    provider.request(prompts.forCase(c, suite.protocol()), settings),
                    response -> ResponseEvaluator.model(c, suite.protocol(), routing, provider.decode(response)))).toList();
            var provenance = provider.inspect();
            manifest.put("provider", provenance.provider())
                    .put("prompt_sha256", Json.hash(prompts.document()))
                    .put("prompt_version", Json.text(prompts.document(), "version"))
                    .put("unreported_sampling_defaults", "Other sampling defaults come from the provider")
                    .put("measurement", "Isolated model HTTP wall time");
            manifest.set("model", Json.MAPPER.valueToTree(provenance.identity()));
            manifest.set("runtime", provenance.runtime());
            manifest.set("settings", settings);
            return capture(files, output, manifest, trials, provider, repetitions, provider, provenance, out);
        }
    }

    private static int capture(PrivateFiles files, Path output, ObjectNode manifest, List<Prepared> trials,
                               TrialRunner.Exchange exchange, int repetitions, ModelProvider provider,
                               ModelProvider.Provenance provenance, PrintStream out)
            throws IOException, InterruptedException {
        var run = files.createCapture(output);
        PrivateFiles.save(run.resolve("manifest.json"), manifest);
        if (provenance != null) {
            PrivateFiles.save(run.resolve("model-info.json"), provenance.details());
        }
        PrivateFiles.save(run.resolve("inputs.json"), Json.MAPPER.valueToTree(trials.stream().map(Prepared::request).toList()));
        var summary = new Reports.Summary();
        var template = Json.object();
        boolean allPassed = true;
        try (var ledger = PrivateFiles.newWriter(run.resolve(Capture.RESULTS))) {
            for (var prepared : trials) {
                for (int trial = 1; trial <= repetitions; trial++) {
                    var row = TrialRunner.execute(prepared.scenario(), prepared.stage(), trial,
                            prepared.request(), exchange, prepared.grader());
                    ledger.write(Json.encode(row));
                    ledger.newLine();
                    ledger.flush();
                    summary.accept(row);
                    HumanReview.addTemplate(template, row);
                    var status = Status.valueOf(row.get("status").asText());
                    allPassed &= status == Status.PASS;
                    out.println(HumanReview.key(row) + " " + status);
                }
            }
        }
        Boolean unchanged = provider == null ? null : provider.unchanged(provenance.identity());
        var stats = summary.json();
        PrivateFiles.save(run.resolve("summary.json"), stats);
        PrivateFiles.write(run.resolve("summary.md"), Reports.markdown(stats));
        PrivateFiles.save(run.resolve("review-template.json"), template);
        PrivateFiles.save(run.resolve("completion.json"), Capture.completion(run, trials.size() * repetitions, unchanged));
        out.println("Private results " + run);
        if (Boolean.FALSE.equals(unchanged)) {
            out.println("Ollama model identity changed or was unavailable at completion");
        }
        return allPassed && !Boolean.FALSE.equals(unchanged) ? 0 : 2;
    }

    private static String runnerFingerprint() throws IOException {
        String runnerPackage = "dev/garmin/evaluation/";
        var location = RunCommand.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        var path = Path.of(java.net.URI.create(location));
        var hashes = Json.object();
        if (Files.isRegularFile(path)) {
            try (var jar = new JarFile(path.toFile())) {
                for (var entry : jar.stream().filter(e -> e.getName().startsWith(runnerPackage)
                        && e.getName().endsWith(".class")).toList()) {
                    try (var bytes = jar.getInputStream(entry)) {
                        hashes.put(entry.getName(), Json.sha256(bytes.readAllBytes()));
                    }
                }
            }
        } else {
            try (var entries = Files.walk(path.resolve(runnerPackage))) {
                for (var entry : entries.filter(e -> e.toString().endsWith(".class")).toList()) {
                    hashes.put(path.relativize(entry).toString(), PrivateFiles.fileHash(entry));
                }
            }
        }
        return Json.hash(hashes);
    }

    private static String fingerprint(Class<?>... types) throws IOException {
        var fingerprints = new ArrayList<String>();
        for (var type : types) {
            try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                if (bytes == null) {
                    throw new IOException("Missing grader class bytes");
                }
                fingerprints.add(Json.sha256(bytes.readAllBytes()));
            }
        }
        return Json.hash(Json.MAPPER.valueToTree(fingerprints));
    }

    private static void addSourceProvenance(Path workspace, ObjectNode manifest) throws InterruptedException {
        String commit = git(workspace, "rev-parse", "HEAD");
        String status = commit == null ? null : git(workspace, "status", "--porcelain");
        manifest.put("source_sha", commit);
        if (status == null) {
            manifest.putNull("source_dirty");
        } else {
            manifest.put("source_dirty", !status.isBlank());
        }
    }

    private static String git(Path workspace, String... arguments) throws InterruptedException {
        try {
            var command = new ArrayList<>(List.of("git", "--no-optional-locks"));
            command.addAll(List.of(arguments));
            var process = new ProcessBuilder(command).directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = process.getInputStream()) {
                String text = new String(input.readAllBytes(), UTF_8).strip();
                return process.waitFor() == 0 ? text : null;
            }
        } catch (IOException unavailable) {
            return null;
        }
    }
}
