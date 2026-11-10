package com.garmin.analytics.integration.ingestion;

import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

/** Launches the standalone Garmin ingestion application with its runtime dependencies. */
public final class GarminDataIngestionJob {

  /**
   * Constructs dependencies and delegates to {@link GarminDataIngestionApp#run(String[])}.
   *
   * @param args the command-line arguments, passed unchanged to the application
   * @throws IOException if configuration or ingestion fails
   * @throws InterruptedException if the HTTP request is interrupted
   */
  static void main(String[] args) throws IOException, InterruptedException {
    new GarminDataIngestionApp(
        new TokenService(System.getenv("GARMIN_TOKEN_FILE")),
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
        new FileWriter()).run(args);
  }
}
