package com.garmin.analytics.integration.sleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.exception.GarminApiException;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import com.garmin.analytics.integration.common.io.FileWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SleepDailyIngestionJobInstanceTest {

  private static final LocalDate DATE = LocalDate.of(2026, 11, 7);
  @TempDir Path directory;
  private MockWebServer server;
  private HttpClient httpClient;
  private SleepDailyIngestionJob job;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    httpClient = HttpClient.newHttpClient();
    job = new SleepDailyIngestionJob(
        new GarminClient(httpClient, server.url("/").uri(), "synthetic-token"),
        new FileWriter());
  }

  @AfterEach
  void tearDown() throws IOException {
    httpClient.close();
    server.shutdown();
  }

  @Test
  void writesToCallerSelectedDirectoriesWithoutAddingSubdirectories() throws Exception {
    Path firstDirectory = directory.resolve("caller-selected-a");
    Path secondDirectory = directory.resolve("caller-selected-b");
    String first = " {\"unknown\":true,\"number\":1.00}\n";
    server.enqueue(new MockResponse().setBody(first));
    job.run(DATE, firstDirectory);

    String second = "[ true, null ]\n";
    server.enqueue(new MockResponse().setBody(second));
    job.run(DATE, secondDirectory);

    assertEquals(first, Files.readString(firstDirectory.resolve(DATE + ".json")));
    assertEquals(second, Files.readString(secondDirectory.resolve(DATE + ".json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "null",
      "[true, null, 1.00]",
      "{\"calendarDate\":\"not-a-date\",\"totalSteps\":\"untyped\",\"sleepTimeSeconds\":-1}",
      "{\"dailySleepDTO\":{\"calendarDate\":\"1900-01-01\",\"newField\":null}}"})
  void archivesValidJsonWithoutFieldOrDateValidation(String rawJson) throws Exception {
    server.enqueue(new MockResponse().setBody(rawJson));
    job.run(DATE, directory);
    assertEquals(rawJson, Files.readString(archive()));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "{",
      "{\"private-measurement\":\"value\"",
      "{} {}",
      "{} trailing"})
  void malformedJsonPreservesCauseAndExistingArchive(String rawJson) throws Exception {
    Files.createDirectories(archive().getParent());
    String previous = "{ \"previous\": true }\n";
    Files.writeString(archive(), previous);
    server.enqueue(new MockResponse().setBody(rawJson));

    InvalidResponseException failure = assertThrows(InvalidResponseException.class,
        () -> job.run(DATE, directory));

    assertInstanceOf(JsonProcessingException.class, failure.getCause());
    assertFalse(failure.getMessage().contains("private-measurement"));
    assertEquals(previous, Files.readString(archive()));
  }

  @Test
  void distinguishesApiFailureWithoutRetryOrArchive() {
    server.enqueue(new MockResponse().setResponseCode(503).setBody("private-api-body"));
    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> job.run(DATE, directory));
    assertTrue(failure.getMessage().contains("HTTP 503"));
    assertFalse(failure.getMessage().contains("private-api-body"));
    assertEquals(1, server.getRequestCount());
    assertFalse(Files.exists(archive()));
  }

  @Test
  void distinguishesArchiveFailureAndPreservesCause() throws Exception {
    Path blocked = directory.resolve("blocked");
    Files.writeString(blocked, "not a directory");
    server.enqueue(new MockResponse().setBody("{}"));

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> job.run(DATE, blocked));

    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals("not a directory", Files.readString(blocked));
  }

  @Test
  void rejectsExistingDestinationWithoutOverwritingIt() throws Exception {
    Files.writeString(archive(), "{\"previous\":true}");
    server.enqueue(new MockResponse().setBody("{\"new\":true}"));

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> job.run(DATE, directory));

    assertInstanceOf(java.nio.file.FileAlreadyExistsException.class, failure.getCause());
    assertEquals("{\"previous\":true}", Files.readString(archive()));
  }

  private Path archive() {
    return directory.resolve(DATE + ".json");
  }
}
