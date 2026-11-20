package dev.garmin.evaluation.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.dataset.PromptFixtures.Prompt;
import dev.garmin.evaluation.transport.JsonHttp;

import java.io.IOException;
import java.util.ArrayList;

public record OllamaProvider(JsonHttp http, String model) implements ModelProvider {
    @Override
    public Provenance inspect() throws IOException, InterruptedException {
        var version = http.exchange("/api/version", null);
        Json.text(version, "version");
        var identity = identity();
        var details = http.exchange("/api/show", Json.object().put("model", model));
        return new Provenance("ollama", identity, version, details);
    }

    private Identity identity() throws IOException, InterruptedException {
        var tags = http.exchange("/api/tags", null).path("models");
        var matches = new ArrayList<JsonNode>();
        for (var tag : tags) {
            if (model.equals(tag.path("name").textValue()) || model.equals(tag.path("model").textValue())) {
                matches.add(tag);
            }
        }
        if (!tags.isArray() || matches.size() != 1 || !matches.getFirst().path("digest").isTextual()
                || matches.getFirst().get("digest").asText().isBlank()) {
            throw new IOException("Requested Ollama model must already be installed with a unique digest");
        }
        return new Identity(model, matches.getFirst().get("digest").asText());
    }

    @Override
    public boolean unchanged(Identity expected) throws InterruptedException {
        try {
            return expected.equals(identity());
        } catch (IOException unavailable) {
            return false;
        }
    }

    @Override
    public JsonNode request(Prompt prompt, JsonNode settings) {
        var request = Json.object().put("model", model).put("stream", false);
        request.set("messages", Json.MAPPER.valueToTree(prompt.messages()));
        request.set("options", settings);
        if (prompt.schema() != null) {
            request.set("format", prompt.schema());
        }
        return request;
    }

    @Override
    public JsonNode send(JsonNode request) throws IOException, InterruptedException {
        return http.exchange("/api/chat", request);
    }

    @Override
    public Completion decode(JsonNode response) throws IOException {
        var message = response.path("message");
        if (!message.isObject()) {
            throw new InvalidModelResponseException("Missing Ollama message object");
        }
        var calls = message.path("tool_calls");
        boolean toolCalls = !calls.isMissingNode() && !calls.isNull() && !(calls.isArray() && calls.isEmpty());
        boolean complete = response.path("done").isBoolean() && response.get("done").booleanValue()
                && !"length".equalsIgnoreCase(response.path("done_reason").asText());
        var content = message.path("content");
        return new Completion(content.isTextual() ? content.asText() : "", complete, toolCalls);
    }

    private static final class InvalidModelResponseException extends IOException {
        InvalidModelResponseException(String message) {
            super(message);
        }
    }
}
