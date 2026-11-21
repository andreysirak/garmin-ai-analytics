package dev.garmin.evaluation.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.Status;
import dev.garmin.evaluation.report.Capture;
import dev.garmin.evaluation.report.PrivateFiles;
import dev.garmin.evaluation.report.Reports;
import dev.garmin.evaluation.review.HumanReview;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class CaptureWriter {
    private final Path directory;
    private final Reports.Summary summary = new Reports.Summary();
    private final ObjectNode reviewTemplate = Json.object();
    private int trialCount;
    private boolean allTrialsPassed = true;

    private CaptureWriter(Path directory) {
        this.directory = directory;
    }

    static CaptureWriter startApplication(Path directory, JsonNode manifest, List<JsonNode> requests)
            throws IOException {
        PrivateFiles.save(directory.resolve("manifest.json"), manifest);
        return writeInputs(directory, requests);
    }

    static CaptureWriter startModel(Path directory, JsonNode manifest, List<JsonNode> requests, JsonNode modelInfo)
            throws IOException {
        PrivateFiles.save(directory.resolve("manifest.json"), manifest);
        PrivateFiles.save(directory.resolve("model-info.json"), modelInfo);
        return writeInputs(directory, requests);
    }

    private static CaptureWriter writeInputs(Path directory, List<JsonNode> requests) throws IOException {
        PrivateFiles.save(directory.resolve("inputs.json"), Json.MAPPER.valueToTree(requests));
        return new CaptureWriter(directory);
    }

    void appendTrial(BufferedWriter ledger, JsonNode row) throws IOException {
        ledger.write(Json.encode(row));
        ledger.newLine();
        ledger.flush();
        summary.accept(row);
        HumanReview.addTemplate(reviewTemplate, row);
        trialCount++;
        allTrialsPassed &= Status.valueOf(row.get("status").asText()) == Status.PASS;
    }

    void publishApplicationCompletion() throws IOException {
        publishCompletion(null);
    }

    void publishModelCompletion(boolean modelUnchanged) throws IOException {
        publishCompletion(modelUnchanged);
    }

    private void publishCompletion(Boolean modelUnchanged) throws IOException {
        var stats = summary.json();
        PrivateFiles.save(directory.resolve("summary.json"), stats);
        PrivateFiles.write(directory.resolve("summary.md"), Reports.markdown(stats));
        PrivateFiles.save(directory.resolve("review-template.json"), reviewTemplate);
        PrivateFiles.save(directory.resolve("completion.json"),
                Capture.completion(directory, trialCount, modelUnchanged));
    }

    boolean allTrialsPassed() {
        return allTrialsPassed;
    }

    Path directory() {
        return directory;
    }
}
