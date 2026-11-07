package com.garmin.analytics.airflow;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.airflow.sdk.Connection;

import com.garmin.analytics.garmin.client.CachingTokenProvider;
import com.garmin.analytics.garmin.client.GarminApiConfig;
import com.garmin.analytics.garmin.client.GarminApiKeyAuthenticator;
import com.garmin.analytics.garmin.client.GarminClient;
import com.garmin.analytics.garmin.service.GarminPayload;
import com.garmin.analytics.garmin.service.GarminSleepService;
import com.garmin.analytics.garmin.service.GarminStepsService;
import com.garmin.analytics.garmin.storage.RawJsonArchive;

public final class GarminTaskFactory {

    private final GarminStepsService stepsService;
    private final GarminSleepService sleepService;
    private final RawJsonArchive archive;

    private GarminTaskFactory(
            GarminStepsService stepsService,
            GarminSleepService sleepService,
            RawJsonArchive archive
    ) {
        this.stepsService = stepsService;
        this.sleepService = sleepService;
        this.archive = archive;
    }

    public static GarminTaskFactory fromConnection(Connection connection) {
        GarminConnectionSettings settings = GarminConnectionSettings.from(connection);
        GarminApiConfig config = settings.toApiConfig();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();

        GarminApiKeyAuthenticator authenticator = new GarminApiKeyAuthenticator(httpClient, config);
        CachingTokenProvider tokenProvider = new CachingTokenProvider(authenticator);
        GarminClient garminClient = new GarminClient(httpClient, tokenProvider, config);

        return new GarminTaskFactory(
                new GarminStepsService(garminClient),
                new GarminSleepService(garminClient),
                new RawJsonArchive(settings.rawBasePath())
        );
    }

    public Map<String, Object> ingestSteps(LocalDate date) throws Exception {
        GarminPayload payload = stepsService.fetchDailySteps(date);
        Path output = archive.persist(payload);
        return metadata(payload, output);
    }

    public Map<String, Object> ingestSleep(LocalDate date) throws Exception {
        GarminPayload payload = sleepService.fetchDailySleep(date);
        Path output = archive.persist(payload);
        return metadata(payload, output);
    }

    private Map<String, Object> metadata(GarminPayload payload, Path output) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dataset", payload.dataset());
        metadata.put("date", payload.date().toString());
        metadata.put("empty", payload.empty());
        metadata.put("path", output.toString());
        return metadata;
    }
}
