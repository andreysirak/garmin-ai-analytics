package dev.garmin.evaluation.transport;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;
import dev.garmin.evaluation.core.TrialRunner;

import java.io.IOException;

public record ChatHttpAdapter(JsonHttp http) implements TrialRunner.Exchange {
    public JsonNode request(String question) {
        return Json.object().put("message", question);
    }

    @Override
    public JsonNode send(JsonNode request) throws IOException, InterruptedException {
        return http.exchange("/api/chat", request);
    }
}
