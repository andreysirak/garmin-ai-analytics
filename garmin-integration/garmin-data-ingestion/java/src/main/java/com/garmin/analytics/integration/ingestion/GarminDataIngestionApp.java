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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one Garmin HTTP request and stores its original JSON in a caller-selected attempt directory.
 *
 * <p>An empty {@code _SUCCESS} file marks a completed attempt. Downstream readers must ignore
 * directories without this marker. The caller supplies a distinct directory for every attempt
 * and owns retry policy. This application closes its injected HTTP client on every exit.
 */
public final class GarminDataIngestionApp {

  private static final Logger LOG = LoggerFactory.getLogger(GarminDataIngestionApp.class);

  private final TokenService tokenService;
  private final HttpClient httpClient;
  private final FileWriter writer;

  /**
   * Creates an ingestion application with its required dependencies.
   *
   * <p>Token loading and response writing are injected through their interfaces. Concrete
   * implementations are selected by the application entry point.
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
   * Parses arguments, fetches one response, and completes one ingestion attempt.
   *
   * <p>The output directory must be new or empty. After JSON syntax validation and a complete
   * write of the unchanged response, an empty {@code _SUCCESS} file is created beside the JSON.
   * Until the marker exists, downstream readers must ignore any partial or complete JSON file
   * in that directory. Existing attempts are never modified or deleted.
   *
   * <p>Standalone {@code --help} prints usage without reading credentials or sending a request.
   * The HTTP client is closed on success and failure, including argument-validation failures.
   *
   * @param args the command-line arguments for this invocation
   * @throws IOException if configuration, token loading, API access, JSON validation, archive
   *     writing, or marker creation fails, or the attempt directory is nonempty
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
      createSuccessMarkerFile(options);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw exception;
    }
  }

  private void createSuccessMarkerFile(GarminApiIntegrationOptions options) throws IOException {
    Path attemptDirectory = options.outputDirectory().toAbsolutePath().normalize();
    Files.createFile(attemptDirectory.resolve("_SUCCESS"));
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
    Path attemptDirectory = destination.toAbsolutePath().normalize().getParent();
    try {
      requireEmptyAttemptDirectory(attemptDirectory);
      writer.write(destination, rawJson);
    } catch (IOException exception) {
      throw new IngestionExecutionException(
          "Could not complete Garmin ingestion attempt", exception);
    }
  }

  private void requireEmptyAttemptDirectory(Path directory) throws IOException {
    if (Files.notExists(directory)) {
      return;
    }
    try (var entries = Files.list(directory)) {
      if (entries.findAny().isPresent()) {
        throw new FileAlreadyExistsException(
            directory.toString(), null, "Attempt directory must be empty");
      }
    }
  }

  private String usage() {
    return """
        Usage: java -jar garmin-data-ingestion.jar \\
          --endpoint /path/to/resource \\
          --date YYYY-MM-DD \\
          --output-dir /path/to/attempt/directory \\
          [--query-param key=value]...
        Repeat --query-param for each request parameter; omit it for a request without parameters.
        --date selects the archive filename and is not added to the request automatically.
        The caller supplies a new or empty directory for each attempt; no subdirectories are added.
        Downstream readers must require _SUCCESS, created only after the JSON write completes.
        Set GARMIN_TOKEN_FILE to a file containing an existing Garmin bearer token.
        """;
  }
}
