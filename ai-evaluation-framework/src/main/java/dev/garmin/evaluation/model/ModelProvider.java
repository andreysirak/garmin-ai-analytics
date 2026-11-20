package dev.garmin.evaluation.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.TrialRunner;
import dev.garmin.evaluation.dataset.PromptFixtures.Prompt;

import java.io.IOException;

public interface ModelProvider extends TrialRunner.Exchange {
    record Identity(String name, String digest) {}
    record Provenance(String provider, Identity identity, JsonNode runtime, JsonNode details) {}
    record Completion(String text, boolean complete, boolean toolCalls) {}

    Provenance inspect() throws IOException, InterruptedException;

    boolean unchanged(Identity identity) throws InterruptedException;

    JsonNode request(Prompt prompt, JsonNode settings);

    Completion decode(JsonNode response) throws IOException;
}
