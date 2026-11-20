package dev.garmin.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.review.HumanReview;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record Capture(JsonNode manifest, JsonNode completion, List<JsonNode> rows) {
    public static final int FORMAT_VERSION = 2;
    public static final String RESULTS = "results.jsonl";

    public static ObjectNode completion(Path run, int trialCount, Boolean modelUnchanged) throws IOException {
        var completion = Json.object().put("completed_at", Instant.now().toString()).put("trial_count", trialCount);
        if (modelUnchanged == null) {
            completion.putNull("model_unchanged");
        } else {
            completion.put("model_unchanged", modelUnchanged);
        }
        var hashes = completion.putObject("artifact_sha256");
        for (String file : List.of("manifest.json", "inputs.json", RESULTS)) {
            hashes.put(file, PrivateFiles.fileHash(run.resolve(file)));
        }
        if (modelUnchanged != null) {
            hashes.put("model-info.json", PrivateFiles.fileHash(run.resolve("model-info.json")));
        }
        return completion;
    }

    public static Capture read(Path run) throws IOException {
        var manifest = PrivateFiles.read(run.resolve("manifest.json"));
        if (manifest.path("format_version").asInt() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Capture is not Java format v" + FORMAT_VERSION);
        }
        var completion = PrivateFiles.read(run.resolve("completion.json"));
        var hashes = completion.required("artifact_sha256");
        for (String file : List.of("manifest.json", "inputs.json", RESULTS)) {
            verifyHash(run, hashes, file);
        }
        var plan = manifest.required("plan");
        boolean application = "application".equals(Json.text(plan, "mode"));
        if (!application) {
            verifyHash(run, hashes, "model-info.json");
        }
        var rows = PrivateFiles.readRows(run.resolve(RESULTS));
        verifyTrials(manifest, completion, PrivateFiles.read(run.resolve("inputs.json")), rows, application);
        return new Capture(manifest, completion, rows);
    }

    public List<JsonNode> comparisonRows(Path run, String results) throws IOException {
        if ("model".equals(manifest.at("/plan/mode").asText())
                && !completion.path("model_unchanged").equals(Json.MAPPER.getNodeFactory().booleanNode(true))) {
            throw new IllegalArgumentException("Cannot compare a run with missing or changed model identity");
        }
        if (RESULTS.equals(results)) {
            return rows;
        }
        if (!results.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*\\.jsonl")) {
            throw new IllegalArgumentException("Expected a results filename inside the capture directory");
        }
        var reviewed = index(PrivateFiles.readRows(run.resolve(results)));
        if (!reviewed.keySet().equals(index(rows).keySet())) {
            throw new IllegalArgumentException("Reviewed results must contain every captured trial");
        }
        for (var raw : rows) {
            var candidate = reviewed.get(HumanReview.key(raw));
            var expected = candidate.has("review") ? HumanReview.applyOne(raw, candidate.get("review")) : raw;
            if (!Json.hash(expected).equals(Json.hash(candidate))) {
                throw new IllegalArgumentException("Reviewed result does not match the captured trial and adjudication");
            }
        }
        return List.copyOf(reviewed.values());
    }

    private static void verifyHash(Path run, JsonNode hashes, String file) throws IOException {
        if (!Json.text(hashes, file).equals(PrivateFiles.fileHash(run.resolve(file)))) {
            throw new IllegalArgumentException("Captured artifact hash mismatch for " + file);
        }
    }

    private static void verifyTrials(JsonNode manifest, JsonNode completion, JsonNode inputs,
                                     List<JsonNode> rows, boolean application) {
        var plan = manifest.required("plan");
        if (!Json.text(manifest, "protocol_sha256").equals(Json.hash(plan))) {
            throw new IllegalArgumentException("Manifest protocol hash mismatch");
        }
        var cases = plan.required("cases");
        var indexed = index(rows);
        int repetitions = plan.required("repetitions").intValue();
        if (!cases.isArray() || cases.isEmpty() || !inputs.isArray() || inputs.size() != cases.size()
                || repetitions < 1 || repetitions > 20 || cases.size() * repetitions != indexed.size()
                || completion.required("trial_count").intValue() != indexed.size()) {
            throw new IllegalArgumentException("Incomplete or unexpected capture trials");
        }
        var remaining = new LinkedHashMap<>(indexed);
        for (int i = 0; i < cases.size(); i++) {
            var scenario = cases.get(i);
            var stage = application ? "application" : Json.text(scenario, "stage");
            var id = Json.text(scenario, "id");
            for (int trial = 1; trial <= repetitions; trial++) {
                var row = remaining.remove(stage + "/" + id + "/" + trial);
                if (row == null || !Json.hash(scenario).equals(Json.text(row, "case_sha256"))
                        || !Json.hash(row.required("input")).equals(Json.text(row, "input_sha256"))
                        || !Json.hash(inputs.get(i)).equals(Json.text(row, "input_sha256"))) {
                    throw new IllegalArgumentException("Missing trial or captured case/input hash mismatch");
                }
                if (row.has("response") != row.has("output_sha256")
                        || row.has("response") && !Json.hash(row.get("response")).equals(Json.text(row, "output_sha256"))) {
                    throw new IllegalArgumentException("Captured response hash mismatch");
                }
            }
        }
    }

    static Map<String, JsonNode> index(List<JsonNode> rows) {
        var indexed = new TreeMap<String, JsonNode>();
        for (var row : rows) {
            Status.valueOf(Json.text(row, "status"));
            if (!row.path("trial").isIntegralNumber() || !row.path("trial").canConvertToInt()
                    || row.get("trial").intValue() < 1 || indexed.put(HumanReview.key(row), row) != null) {
                throw new IllegalArgumentException("Duplicate or invalid trial");
            }
        }
        return indexed;
    }
}
