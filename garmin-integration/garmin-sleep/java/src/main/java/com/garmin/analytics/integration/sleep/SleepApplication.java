package com.garmin.analytics.integration.sleep;

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
public final class SleepApplication {

  private static final Logger LOG = LoggerFactory.getLogger(SleepApplication.class);

  private final TokenService tokenService;

  public SleepApplication(TokenService tokenService) {
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService is required");
  }

  public static void launch(String[] args) throws IOException, InterruptedException {
    Thread.currentThread().setUncaughtExceptionHandler((thread, failure) ->
        FailureLoggingUtil.log(LOG, "Sleep ingestion failed", failure));
    new SleepApplication(new TokenService(System.getenv("GARMIN_TOKEN_FILE"))).run(args);
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
      new SleepDailyIngestionJob(client, writer)
          .run(options.date(), options.outputDirectory());
      LOG.info("Ingested Garmin sleep for {}", options.date());
    }
  }

  private static String usage() {
    return """
        Usage: java -jar garmin-sleep.jar \\
          --date YYYY-MM-DD \\
          --api-base-url https://personal.connectapi.garmin.com \\
          --output-dir ./data/raw/garmin/sleep/YYYY-MM-DD/1
        The caller supplies the final output directory; no subdirectories are added.
        Set GARMIN_TOKEN_FILE to a file containing an existing Garmin bearer token.
        """;
  }
}
