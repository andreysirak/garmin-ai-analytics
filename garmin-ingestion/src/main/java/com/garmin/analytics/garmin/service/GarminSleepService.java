package com.garmin.analytics.garmin.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.garmin.client.GarminHttpClient;

public final class GarminSleepService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GarminHttpClient garminHttpClient;

    public GarminSleepService(GarminHttpClient garminHttpClient) {
        this.garminHttpClient = Objects.requireNonNull(garminHttpClient, "garminHttpClient is required");
    }

    public GarminPayload fetchDailySleep(LocalDate date) throws IOException, InterruptedException {
        String rawJson = garminHttpClient.get(
                "/sleep-service/sleep/dailySleep",
                Map.of("date", date.toString())
        );
        return new GarminPayload("sleep", date, rawJson, isEmptyObject(rawJson));
    }

    private static boolean isEmptyObject(String rawJson) throws IOException {
        JsonNode jsonNode = OBJECT_MAPPER.readTree(rawJson);
        return jsonNode == null
                || jsonNode.isNull()
                || (jsonNode.isObject() && jsonNode.isEmpty())
                || (jsonNode.isArray() && jsonNode.isEmpty());
    }
}
