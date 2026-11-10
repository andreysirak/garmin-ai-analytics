package com.garmin.analytics.integration.ingestion;

import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

/** Application entry point: constructs dependencies and starts ingestion. */
public final class GarminDataIngestionJob {

  static void main(String[] args) throws IOException, InterruptedException {
    new GarminDataIngestionApp(
        new TokenService(System.getenv("GARMIN_TOKEN_FILE")),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        new FileWriter()).run(args);
  }
}
