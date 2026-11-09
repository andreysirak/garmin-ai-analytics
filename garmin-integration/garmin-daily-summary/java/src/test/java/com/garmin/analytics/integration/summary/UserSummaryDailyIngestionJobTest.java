package com.garmin.analytics.integration.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.service.TokenService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserSummaryDailyIngestionJobTest {

  private static final String REQUESTED_DATE = "2026-11-07";
  @TempDir Path temporaryDirectory;
  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void archivesCompleteOriginalResponseOnSuccess() throws Exception {
    String rawJson = " \n{ \"calendarDate\": \"not-a-date\", \"unknown\": 1.00, "
        + "\"note\": \"private-health-value café\", \"nullable\": null }\n";
    server.enqueue(new MockResponse().setBody(rawJson));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("Ingested Garmin daily summary"));
    assertFalse(result.output().contains("private-health-value"));
    assertEquals(rawJson, Files.readString(archive()));
    RecordedRequest request = server.takeRequest();
    assertEquals("/usersummary-service/usersummary/daily?calendarDate=" + REQUESTED_DATE, request.getPath());
    assertEquals("Bearer synthetic-token", request.getHeader("Authorization"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "null", "{\"dailySleepDTO\":{\"calendarDate\":\"1900-01-01\"}}"})
  void acceptsValidJsonWithoutBusinessValidation(String rawJson) throws Exception {
    server.enqueue(new MockResponse().setBody(rawJson));
    ProcessResult result = runJob(REQUESTED_DATE);
    assertEquals(0, result.exitCode());
    assertEquals(rawJson, Files.readString(archive()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"note\":\"private-health-value\"", "{} {}"})
  void logsMalformedJsonOnceWithoutPrivateValues(String rawJson) throws Exception {
    server.enqueue(new MockResponse().setBody(rawJson));
    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("response JSON error"));
    assertTrue(result.output().contains("InvalidResponseException"));
    assertTrue(result.output().contains("details withheld"));
    assertFalse(result.output().contains("private-health-value"));
    assertFalse(result.output().contains("synthetic-token"));
    assertEquals(1, result.output().lines().filter(line -> line.startsWith("ERROR ")).count());
    assertFalse(Files.exists(archive()));
  }

  @Test
  void reportsHttpFailureWithNonzeroExitCode() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("private-api-body"));
    ProcessResult result = runJob(REQUESTED_DATE);
    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("HTTP 503"));
    assertFalse(result.output().contains("private-api-body"));
    assertEquals(1, server.getRequestCount());
    assertFalse(Files.exists(archive()));
  }

  @Test
  void rejectsInvalidRequestDateBeforeMakingRequest() throws Exception {
    ProcessResult result = runJob("2026-02-30");
    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("valid calendar date"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void returnsHelpWithoutRequiringTokenFile() throws Exception {
    ProcessResult result = runJob("--help");
    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("--api-base-url"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void applicationUsesTheInjectedTokenService() throws Exception {
    Path tokenFile = temporaryDirectory.resolve("injected-token");
    Files.writeString(tokenFile, "injected-synthetic-token");
    server.enqueue(new MockResponse().setBody("{}"));
    DailySummaryApplication application = new DailySummaryApplication(new TokenService(tokenFile.toString()));

    application.run(new String[] {
        "--date", REQUESTED_DATE, "--api-base-url", server.url("/").toString(),
        "--output-dir", temporaryDirectory.toString()});

    assertEquals(1, server.getRequestCount());
    assertEquals("Bearer injected-synthetic-token",
        server.takeRequest().getHeader("Authorization"));
  }

  @Test
  void applicationPropagatesFailureToItsCaller() {
    assertThrows(IngestionExecutionException.class,
        () -> new DailySummaryApplication(new TokenService(null)).run(new String[0]));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void separateCliRunsUseTheProvidedFinalDirectories() throws Exception {
    Path firstDirectory = temporaryDirectory.resolve("caller-selected-a");
    Path secondDirectory = temporaryDirectory.resolve("caller-selected-b");
    server.enqueue(new MockResponse().setBody("{\"first\":true}"));
    assertEquals(0, runJob(REQUESTED_DATE, firstDirectory).exitCode());
    server.enqueue(new MockResponse().setBody("{\"second\":true}"));
    assertEquals(0, runJob(REQUESTED_DATE, secondDirectory).exitCode());

    assertEquals("{\"first\":true}",
        Files.readString(firstDirectory.resolve(REQUESTED_DATE + ".json")));
    assertEquals("{\"second\":true}",
        Files.readString(secondDirectory.resolve(REQUESTED_DATE + ".json")));
  }

  private Path archive() {
    return temporaryDirectory.resolve(REQUESTED_DATE + ".json");
  }

  private ProcessResult runJob(String date) throws Exception {
    return runJob(date, temporaryDirectory);
  }

  private ProcessResult runJob(String date, Path outputDirectory) throws Exception {
    Path tokenFile = temporaryDirectory.resolve("token");
    Files.writeString(tokenFile, "synthetic-token\n");
    String classpath =
        System.getProperty(
            "surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            UserSummaryDailyIngestionJob.class.getName());
    if ("--help".equals(date)) {
      builder.command().add("--help");
    } else {
      builder.command().addAll(
          java.util.List.of(
              "--date",
              date,
              "--api-base-url",
              server.url("/").toString(),
              "--output-dir",
              outputDirectory.toString()));
    }
    if ("--help".equals(date)) {
      builder.environment().remove("GARMIN_TOKEN_FILE");
    } else {
      builder.environment().put("GARMIN_TOKEN_FILE", tokenFile.toString());
    }
    builder.redirectErrorStream(true);
    Process process = builder.start();
    boolean completed = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
      throw new AssertionError("Daily summary CLI did not finish in time");
    }
    return new ProcessResult(
        process.exitValue(), new String(process.getInputStream().readAllBytes()));
  }

  private record ProcessResult(int exitCode, String output) {}
}
