package com.garmin.analytics.integration.ingestion;

import com.garmin.analytics.integration.common.cli.GarminApiIntegrationOptions;
import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import com.garmin.analytics.integration.common.util.GarminApiIntegrationCliUtil;
import com.garmin.analytics.integration.common.util.JsonValidationUtil;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one Garmin HTTP request and archives the original JSON response.
 *
 * <p>This single-run application closes its injected HTTP client on every exit, including help.
 * Failures propagate to the caller, which owns retry policy.
 */
public final class GarminDataIngestionApp {

  private static final Logger LOG = LoggerFactory.getLogger(GarminDataIngestionApp.class);

  private final TokenService tokenService;
  private final HttpClient httpClient;
  private final FileWriter writer;

  /**
   * Creates an ingestion application with its required dependencies.
   *
   * @param tokenService the service that reads and validates the bearer token
   * @param httpClient the configured HTTP client, closed by {@link #run(String[])}
   * @param writer the writer that archives the unchanged response text
   * @throws NullPointerException if any dependency is {@code null}
   */
  public GarminDataIngestionApp(
      TokenService tokenService, HttpClient httpClient, FileWriter writer) {
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService is required");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
    this.writer = Objects.requireNonNull(writer, "writer is required");
  }

  /**
   * Parses arguments, fetches one response, and archives its original JSON text.
   *
   * <p>Standalone {@code --help} prints usage without reading credentials or sending a request.
   * The HTTP client is closed on success and failure, including argument-validation failures.
   *
   * @param args the command-line arguments for this invocation
   * @throws IOException if configuration, token loading, API access, JSON validation, or archive
   *     writing fails
   * @throws InterruptedException if the request is interrupted; the interrupt flag is restored
   *     before the exception propagates
   */
  public void run(String[] args) throws IOException, InterruptedException {
    try (httpClient) {
      if (GarminApiIntegrationCliUtil.isHelp(args)) {
        LOG.info(usage());
        return;
      }
      GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(args);
      ingest(options);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    }
  }

  private void ingest(GarminApiIntegrationOptions options)
      throws IOException, InterruptedException {
    GarminClient client = new GarminClient(httpClient, tokenService.readBearerToken());
    String rawJson = client.callGet(options.endpoint(), options.queryParameters());
    JsonValidationUtil.validate(rawJson);
    archive(options.outputDirectory().resolve(options.date() + ".json"), rawJson);
    LOG.info("Ingested Garmin response for {}", options.date());
  }

  private void archive(Path destination, String rawJson)
      throws IngestionExecutionException {
    try {
      writer.write(destination, rawJson);
    } catch (IOException exception) {
      throw new IngestionExecutionException(
          "Could not archive Garmin response", exception);
    }
  }

  private String usage() {
    return """
        Usage: java -jar garmin-data-ingestion.jar \\
          --endpoint /path/to/resource \\
          --date YYYY-MM-DD \\
          --output-dir /path/to/final/directory \\
          [--query-param key=value]...
        Repeat --query-param for each request parameter; omit it for a request without parameters.
        --date selects the archive filename and is not added to the request automatically.
        The caller supplies the final output directory; no subdirectories are added.
        Set GARMIN_TOKEN_FILE to a file containing an existing Garmin bearer token.
        """;
  }
}
