package com.garmin.analytics.integration.summary;

import com.garmin.analytics.integration.common.util.GarminApiIntegrationCliUtil;
import com.garmin.analytics.integration.common.cli.GarminApiIntegrationOptions;
import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import com.garmin.analytics.integration.common.util.FailureLoggingUtil;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CLI boundary: assembles dependencies and reports the outcome exactly once. */
public final class DailySummaryApplication {

  private static final Logger LOG = LoggerFactory.getLogger(DailySummaryApplication.class);

  private final TokenService tokenService;

  public DailySummaryApplication(TokenService tokenService) {
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService is required");
  }

  public static void launch(String[] args) throws IOException, InterruptedException {
    Thread.currentThread().setUncaughtExceptionHandler((thread, failure) ->
        FailureLoggingUtil.log(LOG, "Daily summary ingestion failed", failure));
    new DailySummaryApplication(new TokenService(System.getenv("GARMIN_TOKEN_FILE"))).run(args);
  }

  public void run(String[] args) throws IOException, InterruptedException {
    if (GarminApiIntegrationCliUtil.isHelp(args)) {
      LOG.info(usage());
      return;
    }
    try {
      execute(args);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    }
  }

  private void execute(String[] args) throws IOException, InterruptedException {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(args);
    String token = tokenService.readBearerToken();
    try (HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build()) {
      GarminClient client = new GarminClient(httpClient, options.baseUri(), token);
      FileWriter writer = new FileWriter();
      new UserSummaryDailyIngestionJob(client, writer)
          .run(options.date(), options.outputDirectory());
      LOG.info("Ingested Garmin daily summary for {}", options.date());
    }
  }

  private String usage() {
    return """
        Usage: java -jar garmin-daily-summary.jar \\
          --date YYYY-MM-DD \\
          --api-base-url https://personal.connectapi.garmin.com \\
          --output-dir ./data/raw/garmin/daily-summary/YYYY-MM-DD/1
        The caller supplies the final output directory; no subdirectories are added.
        Set GARMIN_TOKEN_FILE to a file containing an existing Garmin bearer token.
        """;
  }
}
