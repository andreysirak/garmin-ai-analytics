package com.garmin.analytics.integration.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class SleepDailyIngestionJobTest {

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
    String rawJson =
        """
        {"dailySleepDTO":{"calendarDate":"2026-11-07","sleepTimeSeconds":27000,"unknown":true}}
        """;
    server.enqueue(new MockResponse().setBody(rawJson));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("Ingested Garmin sleep"));
    assertEquals(
        rawJson,
        Files.readString(temporaryDirectory.resolve("sleep").resolve(REQUESTED_DATE + ".json")));
    RecordedRequest request = server.takeRequest();
    assertEquals(
        "/sleep-service/sleep/dailySleep?date=" + REQUESTED_DATE,
        request.getPath());
    assertEquals("Bearer synthetic-token", request.getHeader("Authorization"));
  }

  @Test
  void rejectsMismatchedResponseDateAndDoesNotArchive() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"dailySleepDTO\":{\"calendarDate\":\"2026-11-06\","
                    + "\"sleepTimeSeconds\":27000}}"));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("missing or mismatched calendarDate"));
    assertFalse(Files.exists(temporaryDirectory.resolve("sleep").resolve(REQUESTED_DATE + ".json")));
  }

  @Test
  void reportsHttpFailureWithNonzeroExitCode() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(503));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("HTTP 503"));
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void returnsHelpWithoutRequiringTokenFile() throws Exception {
    ProcessResult result = runJob("--help");

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("--output-dir"));
    assertEquals(0, server.getRequestCount());
  }

  private ProcessResult runJob(String date) throws Exception {
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
            SleepDailyIngestionJob.class.getName());
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
              temporaryDirectory.toString()));
    }
    builder.environment().put("GARMIN_TOKEN_FILE", tokenFile.toString());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    boolean completed = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
      throw new AssertionError("Sleep CLI did not finish in time");
    }
    return new ProcessResult(
        process.exitValue(), new String(process.getInputStream().readAllBytes()));
  }

  private record ProcessResult(int exitCode, String output) {}
}
