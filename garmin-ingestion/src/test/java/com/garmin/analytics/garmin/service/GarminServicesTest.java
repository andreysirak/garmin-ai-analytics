package com.garmin.analytics.garmin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.garmin.analytics.garmin.client.GarminHttpClient;

class GarminServicesTest {

    @Test
    void stepsServiceUsesExpectedEndpointAndHandlesEmptyPayload() throws Exception {
        LocalDate targetDate = LocalDate.of(2026, 11, 5);

        CapturingClient client = new CapturingClient("[]");
        GarminStepsService service = new GarminStepsService(client);

        GarminPayload payload = service.fetchDailySteps(targetDate);

        assertEquals("/usersummary-service/stats/steps/daily/2026-11-05/2026-11-05", client.path);
        assertTrue(client.query.isEmpty());
        assertTrue(payload.empty());
    }

    @Test
    void sleepServiceUsesExpectedEndpointAndHandlesEmptyPayload() throws Exception {
        LocalDate targetDate = LocalDate.of(2026, 11, 5);

        CapturingClient client = new CapturingClient("{}");
        GarminSleepService service = new GarminSleepService(client);

        GarminPayload payload = service.fetchDailySleep(targetDate);

        assertEquals("/sleep-service/sleep/dailySleep", client.path);
        assertEquals("2026-11-05", client.query.get("date"));
        assertTrue(payload.empty());
    }

    private static final class CapturingClient implements GarminHttpClient {
        private final String response;
        private String path;
        private Map<String, String> query;

        private CapturingClient(String response) {
            this.response = response;
        }

        @Override
        public String get(String path, Map<String, String> queryParams) {
            this.path = path;
            this.query = queryParams;
            return response;
        }
    }
}
