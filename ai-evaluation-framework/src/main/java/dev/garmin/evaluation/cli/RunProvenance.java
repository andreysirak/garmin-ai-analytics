package dev.garmin.evaluation.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.dataset.EvidenceOracle;
import dev.garmin.evaluation.dataset.PromptFixtures;
import dev.garmin.evaluation.evaluator.ResponseEvaluator;
import dev.garmin.evaluation.evaluator.RoutingEvaluator;
import dev.garmin.evaluation.model.ModelProvider;
import dev.garmin.evaluation.report.PrivateFiles;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static java.nio.charset.StandardCharsets.UTF_8;

final class RunProvenance {
    private static final String RUNNER_PACKAGE = "dev/garmin/evaluation/";

    private RunProvenance() {}

    static void addModelProvenance(ObjectNode manifest, ModelProvider.Provenance provenance,
                                   PromptFixtures prompts, JsonNode modelSettings) {
        manifest.put("provider", provenance.provider())
                .put("prompt_sha256", Json.hash(prompts.document()))
                .put("prompt_version", Json.text(prompts.document(), "version"))
                .put("unreported_sampling_defaults", "Other sampling defaults come from the provider")
                .put("measurement", "Isolated model HTTP wall time");
        manifest.set("model", Json.MAPPER.valueToTree(provenance.identity()));
        manifest.set("runtime", provenance.runtime());
        manifest.set("settings", modelSettings);
    }

    static String graderFingerprint() throws IOException {
        var graders = List.of(ResponseEvaluator.class, RoutingEvaluator.class, EvidenceOracle.class);
        var hashes = new ArrayList<String>();
        for (var grader : graders) {
            hashes.add(hashClassBytes(grader));
        }
        return Json.hash(Json.MAPPER.valueToTree(hashes));
    }

    private static String hashClassBytes(Class<?> type) throws IOException {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (bytes == null) {
                throw new IOException("Missing grader class bytes");
            }
            return Json.sha256(bytes.readAllBytes());
        }
    }

    static String runnerFingerprint() throws IOException {
        var location = RunCommand.class.getProtectionDomain().getCodeSource().getLocation();
        var artifact = Path.of(URI.create(location.toExternalForm()));
        return Json.hash(Files.isRegularFile(artifact) ? hashJarClasses(artifact) : hashExplodedClasses(artifact));
    }

    private static ObjectNode hashJarClasses(Path artifact) throws IOException {
        var hashes = Json.object();
        try (var jar = new JarFile(artifact.toFile())) {
            var classes = jar.stream().filter(entry -> entry.getName().startsWith(RUNNER_PACKAGE)
                    && entry.getName().endsWith(".class")).toList();
            for (var entry : classes) {
                try (var bytes = jar.getInputStream(entry)) {
                    hashes.put(entry.getName(), Json.sha256(bytes.readAllBytes()));
                }
            }
        }
        return hashes;
    }

    private static ObjectNode hashExplodedClasses(Path classes) throws IOException {
        var hashes = Json.object();
        try (var entries = Files.walk(classes.resolve(RUNNER_PACKAGE))) {
            for (var entry : entries.filter(path -> path.toString().endsWith(".class")).toList()) {
                hashes.put(classes.relativize(entry).toString(), PrivateFiles.fileHash(entry));
            }
        }
        return hashes;
    }

    static void addSourceRevision(Path workspace, ObjectNode manifest) throws InterruptedException {
        String commit = readGitOutput(workspace, "rev-parse", "HEAD");
        String status = commit == null ? null : readGitOutput(workspace, "status", "--porcelain");
        manifest.put("source_sha", commit);
        if (status == null) {
            manifest.putNull("source_dirty");
        } else {
            manifest.put("source_dirty", !status.isBlank());
        }
    }

    private static String readGitOutput(Path workspace, String... arguments) throws InterruptedException {
        var command = new ArrayList<>(List.of("git", "--no-optional-locks"));
        command.addAll(List.of(arguments));
        try {
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
