package dev.garmin.evaluation.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

@FunctionalInterface
public interface JsonHttp {
    /** A null payload means GET. Returns a JSON object. */
    JsonNode exchange(String path, JsonNode payload) throws IOException, InterruptedException;
}
