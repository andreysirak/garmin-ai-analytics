package com.garmin.analytics.integration.summary;

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
    String rawJson =
        """
        {"calendarDate":"2026-11-07","totalSteps":8100,"wellnessStartTimeGmt":"2026-11-06T23:00:00.0","wellnessStartTimeLocal":"2026-11-07T00:00:00.0","floorsAscended":7.66339,"bodyBatteryVersion":2.0,"unknownGarminField":{"value":true}}
        """;
    server.enqueue(new MockResponse().setBody(rawJson));
    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("Ingested Garmin daily summary"));
    assertEquals(
        rawJson,
        Files.readString(
            temporaryDirectory.resolve("daily-summary").resolve(REQUESTED_DATE + ".json")));

    RecordedRequest request = server.takeRequest();
    assertEquals(
        "/usersummary-service/usersummary/daily?calendarDate=" + REQUESTED_DATE,
        request.getPath());
    assertEquals("Bearer synthetic-token", request.getHeader("Authorization"));
  }

  @Test
  void archivesFlatSummaryWithOriginalJsonUnchanged() throws Exception {
    String rawJson =
        """
        {"calendarDate":"2026-11-07","totalSteps":8100,
         "additionalGarminData":{"nested":true}}
        """;
    server.enqueue(new MockResponse().setBody(rawJson));
    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(0, result.exitCode());
    assertEquals(
        rawJson,
        Files.readString(
            temporaryDirectory.resolve("daily-summary").resolve(REQUESTED_DATE + ".json")));
  }

  @Test
  void rejectsResponseWithoutCalendarDate() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                "{\"totalSteps\":8100}"));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("missing or mismatched calendarDate"));
    assertFalse(
        Files.exists(
            temporaryDirectory.resolve("daily-summary").resolve(REQUESTED_DATE + ".json")));
  }

  @Test
  void rejectsMismatchedResponseDateAndDoesNotArchive() throws Exception {
    server.enqueue(
        new MockResponse().setBody("{\"calendarDate\":\"2026-11-06\",\"totalSteps\":1}"));

    ProcessResult result = runJob(REQUESTED_DATE);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("missing or mismatched calendarDate"));
    assertFalse(
        Files.exists(
            temporaryDirectory.resolve("daily-summary").resolve(REQUESTED_DATE + ".json")));
  }

  @Test
  void rejectsInvalidDateBeforeMakingRequest() throws Exception {
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
              temporaryDirectory.toString()));
    }
    builder.environment().put("GARMIN_TOKEN_FILE", tokenFile.toString());
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
