package com.garmin.analytics.garmin.service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garmin.analytics.garmin.client.GarminHttpClient;

public final class GarminStepsService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GarminHttpClient garminHttpClient;

    public GarminStepsService(GarminHttpClient garminHttpClient) {
        this.garminHttpClient = Objects.requireNonNull(garminHttpClient, "garminHttpClient is required");
    }

    public GarminPayload fetchDailySteps(LocalDate date) throws IOException, InterruptedException {
        String dateValue = date.toString();
        String path = "/usersummary-service/stats/steps/daily/" + dateValue + "/" + dateValue;
        String rawJson = garminHttpClient.get(path, Map.of());
        return new GarminPayload("steps", date, rawJson, isEmptyArray(rawJson));
    }

    private static boolean isEmptyArray(String rawJson) throws IOException {
        JsonNode jsonNode = OBJECT_MAPPER.readTree(rawJson);
        return jsonNode == null || jsonNode.isNull() || (jsonNode.isArray() && jsonNode.isEmpty());
    }
}
