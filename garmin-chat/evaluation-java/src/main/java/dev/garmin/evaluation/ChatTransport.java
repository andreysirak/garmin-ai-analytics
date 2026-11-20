package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

@FunctionalInterface
public interface ChatTransport {
    JsonNode send(JsonNode request) throws IOException, InterruptedException;
}