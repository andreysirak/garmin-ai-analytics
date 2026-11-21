package dev.garmin.evaluation.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.TrialRunner;
import dev.garmin.evaluation.dataset.EvaluationSuite;
import dev.garmin.evaluation.dataset.PromptFixtures;
import dev.garmin.evaluation.evaluator.ResponseEvaluator;
import dev.garmin.evaluation.evaluator.RoutingEvaluator;
import dev.garmin.evaluation.model.ModelProvider;
import dev.garmin.evaluation.model.OllamaProvider;
import dev.garmin.evaluation.report.Capture;
import dev.garmin.evaluation.report.PrivateFiles;
import dev.garmin.evaluation.review.HumanReview;
import dev.garmin.evaluation.transport.ChatHttpAdapter;
import dev.garmin.evaluation.transport.LoopbackHttp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RunCommand {
    private final EvaluationCli.Options options;
    private final Path workspace;
    private final PrintStream progress;
    private final LedgerOpener ledgerOpener;

    @FunctionalInterface
    interface LedgerOpener {
        BufferedWriter open(Path ledger) throws IOException;
    }

    RunCommand(EvaluationCli.Options options, Path workspace, PrintStream progress, LedgerOpener ledgerOpener) {
        this.options = options;
        this.workspace = workspace;
        this.progress = progress;
        this.ledgerOpener = ledgerOpener;
    }

    static int execute(EvaluationCli.Options options, Path workspace, PrintStream out)
            throws IOException, InterruptedException {
        return new RunCommand(options, workspace, out, PrivateFiles::newWriter).run();
    }

    int run() throws IOException, InterruptedException {
        var selection = selectScenarios(EvaluationSuite.load());
        var files = preflightCapture();
        var manifest = createManifest(selection);
        return switch (selection.settings().mode()) {
            case APPLICATION -> evaluateApplication(selection, files, manifest);
            case MODEL -> evaluateModel(selection, files, manifest);
        };
    }

    private RunSelection selectScenarios(EvaluationSuite suite) {
        var settings = readRunSettings();
        String scenarioStage = settings.mode() == RunMode.APPLICATION ? "routing" : settings.stage();
        return new RunSelection(suite, settings, suite.select(settings.split(), scenarioStage));
    }

    private RunSettings readRunSettings() {
        var mode = RunMode.valueOf(options.choice("mode", "model", "model", "application").toUpperCase(Locale.ROOT));
        if (mode == RunMode.APPLICATION) {
            requireSyntheticDeployment();
        }
        String split = options.choice("split", "regression", "regression", "holdout", "all");
        String stage = options.choice("stage", "all", "routing", "explanation", "all");
        int repetitions = options.integer("repetitions", 3, 1, 20);
        var timeout = options.decimal("timeout", "120", BigDecimal.ZERO, new BigDecimal("600"));
        if (timeout.signum() == 0) {
            throw new IllegalArgumentException("HTTP timeout must be positive");
        }
        return new RunSettings(mode, split, stage, repetitions, timeout);
    }

    private void requireSyntheticDeployment() {
        if (!options.has("synthetic-database")) {
            throw new IllegalArgumentException("Application mode requires --synthetic-database and --deployment-id");
        }
        options.required("deployment-id");
    }

    private Path requestedOutput() {
        return Path.of(options.required("output"));
    }

    private PrivateFiles preflightCapture() throws IOException {
        var files = new PrivateFiles(workspace);
        files.destination(requestedOutput());
        return files;
    }

    private ObjectNode createManifest(RunSelection selection) throws IOException, InterruptedException {
        var plan = describePlan(selection);
        var manifest = Json.object().put("format_version", Capture.FORMAT_VERSION)
                .put("started_at", Instant.now().toString()).put("protocol_sha256", Json.hash(plan))
                .put("runner_sha256", RunProvenance.runnerFingerprint())
                .put("java_version", Runtime.version().toString())
                .put("timeout_seconds", selection.settings().timeoutSeconds());
        manifest.set("plan", plan);
        RunProvenance.addSourceRevision(workspace, manifest);
        return manifest;
    }

    private static ObjectNode describePlan(RunSelection selection) throws IOException {
        var suite = selection.suite();
        var settings = selection.settings();
        String captureStage = settings.mode() == RunMode.APPLICATION ? "application" : settings.stage();
        var plan = Json.object().put("suite_version", suite.version()).put("rubric_version", suite.rubricVersion())
                .put("split", settings.split()).put("stage", captureStage)
                .put("mode", settings.mode().name().toLowerCase(Locale.ROOT))
                .put("repetitions", settings.repetitions()).put("grader_sha256", RunProvenance.graderFingerprint());
        plan.set("cases", Json.MAPPER.valueToTree(selection.scenarios()));
        plan.set("protocol", suite.protocol());
        return plan;
    }

    private int evaluateApplication(RunSelection selection, PrivateFiles files, ObjectNode manifest)
            throws IOException, InterruptedException {
        var settings = selection.settings();
        describeApplicationRun(manifest, options.required("deployment-id"));
        try (var http = new LoopbackHttp(options.get("application-url", "http://127.0.0.1:8081"), settings.httpDeadline())) {
            var adapter = new ChatHttpAdapter(http);
            var trials = prepareApplicationTrials(selection.scenarios(), adapter);
            var directory = files.createCapture(requestedOutput());
            var capture = CaptureWriter.startApplication(directory, manifest, requests(trials));
            recordTrials(capture, trials, adapter, settings.repetitions());
            capture.publishApplicationCompletion();
            return reportOutcome(capture, true);
        }
    }

    private static void describeApplicationRun(ObjectNode manifest, String deploymentId) {
        manifest.put("deployment_id", deploymentId).put("measurement", "Public application HTTP wall time");
    }

    private static List<PreparedTrial> prepareApplicationTrials(List<JsonNode> scenarios, ChatHttpAdapter adapter) {
        return scenarios.stream().map(scenario -> new PreparedTrial(scenario, "application",
                adapter.request(Json.text(scenario, "question")), ResponseEvaluator::application)).toList();
    }

    private int evaluateModel(RunSelection selection, PrivateFiles files, ObjectNode manifest)
            throws IOException, InterruptedException {
        var prompts = PromptFixtures.load();
        var modelSettings = readModelSettings(selection.suite().protocol());
        try (var http = new LoopbackHttp(options.get("ollama-url", "http://127.0.0.1:11434"), selection.settings().httpDeadline())) {
            var provider = new OllamaProvider(http, options.get("model", "qwen2.5:1.5b-instruct-q4_K_M"));
            var trials = prepareModelTrials(selection, provider, prompts, modelSettings);
            var provenance = provider.inspect();
            RunProvenance.addModelProvenance(manifest, provenance, prompts, modelSettings);
            var directory = files.createCapture(requestedOutput());
            var capture = CaptureWriter.startModel(directory, manifest, requests(trials), provenance.details());
            recordTrials(capture, trials, provider, selection.settings().repetitions());
            boolean modelUnchanged = provider.unchanged(provenance.identity());
            capture.publishModelCompletion(modelUnchanged);
            return reportOutcome(capture, modelUnchanged);
        }
    }

    private ObjectNode readModelSettings(JsonNode protocol) {
        int contextTokens = protocol.get("context_tokens").intValue();
        int responseTokens = protocol.get("response_tokens").intValue();
        return Json.object()
                .put("temperature", options.decimal("temperature", "0", BigDecimal.ZERO, new BigDecimal("2")))
                .put("num_ctx", options.integer("num-ctx", contextTokens, 1, Integer.MAX_VALUE))
                .put("num_predict", options.integer("num-predict", responseTokens, 1, Integer.MAX_VALUE))
                .put("num_gpu", options.integer("num-gpu", 0, 0, Integer.MAX_VALUE));
    }

    private static List<PreparedTrial> prepareModelTrials(RunSelection selection, ModelProvider provider,
                                                         PromptFixtures prompts, JsonNode modelSettings) {
        var protocol = selection.suite().protocol();
        var routing = new RoutingEvaluator(protocol);
        var trials = new ArrayList<PreparedTrial>();
        for (var scenario : selection.scenarios()) {
            var prompt = prompts.forCase(scenario, protocol);
            var request = provider.request(prompt, modelSettings);
            TrialRunner.Grader grader = response ->
                    ResponseEvaluator.model(scenario, protocol, routing, provider.decode(response));
            trials.add(new PreparedTrial(scenario, Json.text(scenario, "stage"), request, grader));
        }
        return trials;
    }

    private static List<JsonNode> requests(List<PreparedTrial> trials) {
        return trials.stream().map(PreparedTrial::request).toList();
    }

    private void recordTrials(CaptureWriter capture, List<PreparedTrial> trials, TrialRunner.Exchange exchange,
                              int repetitions) throws IOException, InterruptedException {
        try (var ledger = ledgerOpener.open(capture.directory().resolve(Capture.RESULTS))) {
            for (var prepared : trials) {
                for (int trial = 1; trial <= repetitions; trial++) {
                    var row = TrialRunner.execute(prepared.scenario(), prepared.stage(), trial,
                            prepared.request(), exchange, prepared.grader());
                    capture.appendTrial(ledger, row);
                    progress.println(HumanReview.key(row) + " " + row.get("status").asText());
                }
            }
        }
    }

    private int reportOutcome(CaptureWriter capture, boolean identityAccepted) {
        progress.println("Private results " + capture.directory());
        if (!identityAccepted) {
            progress.println("Ollama model identity changed or was unavailable at completion");
        }
        return capture.allTrialsPassed() && identityAccepted ? 0 : 2;
    }

    private enum RunMode { APPLICATION, MODEL }

    private record RunSettings(RunMode mode, String split, String stage,
                               int repetitions, BigDecimal timeoutSeconds) {
        Duration httpDeadline() {
            return Duration.ofNanos(timeoutSeconds.movePointRight(9).longValue());
        }
    }

    private record RunSelection(EvaluationSuite suite, RunSettings settings, List<JsonNode> scenarios) {}

    private record PreparedTrial(JsonNode scenario, String stage, JsonNode request, TrialRunner.Grader grader) {}
}
