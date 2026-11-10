package com.garmin.analytics.integration.ingestion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.garmin.analytics.integration.common.exception.GarminApiException;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class GarminDataIngestionAppTest {

  private static final String DATE = "2026-11-07";
  private static final String ENDPOINT = "/caller/resource";
  private static final String DATE_PARAMETER = "callerDate";
  @TempDir Path directory;
  private HttpClient httpClient;
  private HttpResponse<String> response;
  private TokenService tokenService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    httpClient = mock(HttpClient.class);
    response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(httpClient.<String>send(any(), any())).thenReturn(response);
    Path tokenFile = directory.resolve("token");
    Files.writeString(tokenFile, "synthetic-injected-token\n");
    tokenService = new TokenService(tokenFile.toString());
  }

  @ParameterizedTest
  @CsvSource({
      "/sleep-service/sleep/dailySleep,date",
      "/usersummary-service/usersummary/daily,calendarDate",
      "/another-service/v9/resource,requestedDate"})
  void forwardsCallerSelectedRequestAndPreservesResponse(String endpoint, String dateParameter)
      throws Exception {
    String rawJson = " \r\n{ \"unknown\": \"café\", \"number\": 1.00, \"value\": null }\n";
    when(response.body()).thenReturn(rawJson);

    application().run(arguments(directory, endpoint, dateParameter + "=" + DATE));

    assertArrayEquals(rawJson.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(archive()));
    ArgumentCaptor<HttpRequest> sentRequest = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(sentRequest.capture(), any());
    HttpRequest request = sentRequest.getValue();
    assertEquals("GET", request.method());
    assertEquals(URI.create("https://personal.connectapi.garmin.com" + endpoint
        + "?" + dateParameter + "=" + DATE), request.uri());
    assertEquals(Optional.of("Bearer synthetic-injected-token"),
        request.headers().firstValue("Authorization"));
    assertEquals(Optional.of("application/json"), request.headers().firstValue("Accept"));
    verify(httpClient).close();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}", "null", "[true, null, 1.00]", "{\"calendarDate\":\"not-a-date\"}",
      "{\"sleepTimeSeconds\":-1,\"totalSteps\":\"untyped\"}"})
  void acceptsValidJsonWithoutBusinessValidation(String rawJson) throws Exception {
    when(response.body()).thenReturn(rawJson);

    application().run(arguments(directory));

    assertEquals(rawJson, Files.readString(archive()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "{", "{\"private-health-value\":1", "{} {}", "{} trailing"})
  void malformedJsonDoesNotChangeEarlierData(String rawJson) throws Exception {
    Files.writeString(archive(), "{\"previous\":true}");
    when(response.body()).thenReturn(rawJson);

    InvalidResponseException failure = assertThrows(InvalidResponseException.class,
        () -> application().run(arguments(directory)));

    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private-health-value"));
    assertEquals("{\"previous\":true}", Files.readString(archive()));
    verify(httpClient).close();
  }

  @Test
  void propagatesApiFailureWithoutRetrying() throws Exception {
    when(response.statusCode()).thenReturn(503);
    when(response.body()).thenReturn("private-api-body");

    GarminApiException failure = assertThrows(GarminApiException.class,
        () -> application().run(arguments(directory)));

    assertTrue(failure.getMessage().contains("HTTP 503"));
    assertFalse(failure.getMessage().contains("private-api-body"));
    verify(httpClient).send(any(), any());
    verify(httpClient).close();
    assertFalse(Files.exists(archive()));
  }

  @Test
  void propagatesArchiveFailureWithItsCause() throws Exception {
    Path blocked = directory.resolve("blocked");
    Files.writeString(blocked, "preserved");
    when(response.body()).thenReturn("{}");

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> application().run(arguments(blocked)));

    assertInstanceOf(java.io.IOException.class, failure.getCause());
    assertEquals("preserved", Files.readString(blocked));
  }

  @Test
  void rejectsExistingArchivesWithoutOverwritingThem() throws Exception {
    Files.writeString(archive(), "{\"previous\":true}");
    when(response.body()).thenReturn("{\"new\":true}");

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> application().run(arguments(directory)));

    assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
    assertEquals("{\"previous\":true}", Files.readString(archive()));
  }

  @Test
  void validatesArgumentsBeforeReadingTheToken() throws Exception {
    GarminDataIngestionApp app = new GarminDataIngestionApp(
        new TokenService(null), httpClient, new FileWriter());

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> app.run(arguments(directory, "relative/path", DATE_PARAMETER + "=" + DATE)));

    assertTrue(failure.getMessage().contains("--endpoint"));
    verify(httpClient, never()).send(any(), any());
  }

  @Test
  void helpNeedsNoTokenAndClosesTheHttpClient() throws Exception {
    GarminDataIngestionApp app = new GarminDataIngestionApp(
        new TokenService(null), httpClient, new FileWriter());

    app.run(new String[] {"--help"});

    verify(httpClient).close();
    verify(httpClient, never()).send(any(), any());
  }

  @Test
  void propagatesInterruptionAndRestoresTheInterruptFlag() throws Exception {
    when(httpClient.<String>send(any(), any()))
        .thenThrow(new InterruptedException("synthetic interruption"));
    try {
      assertThrows(InterruptedException.class, () -> application().run(arguments(directory)));
      assertTrue(Thread.currentThread().isInterrupted());
      verify(httpClient).close();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void forwardsMultipleQueryParametersWithoutInterpretingTheirValues() throws Exception {
    when(response.body()).thenReturn("{}");

    application().run(arguments(directory, ENDPOINT,
        "from=2026-10-01", "limit=25", "label=café /+?", "empty=", "expression=a=b"));

    ArgumentCaptor<HttpRequest> sentRequest = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(sentRequest.capture(), any());
    assertEquals(URI.create("https://personal.connectapi.garmin.com/caller/resource"
        + "?from=2026-10-01&limit=25&label=caf%C3%A9+%2F%2B%3F&empty=&expression=a%3Db"),
        sentRequest.getValue().uri());
    assertEquals("{}", Files.readString(archive()));
  }

  @Test
  void archiveDateIsNotAutomaticallySentAsAQueryParameter() throws Exception {
    when(response.body()).thenReturn("{}");

    application().run(arguments(directory, ENDPOINT));

    ArgumentCaptor<HttpRequest> sentRequest = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(sentRequest.capture(), any());
    assertEquals(URI.create("https://personal.connectapi.garmin.com/caller/resource"),
        sentRequest.getValue().uri());
    assertNull(sentRequest.getValue().uri().getRawQuery());
    assertEquals("{}", Files.readString(archive()));
  }

  private GarminDataIngestionApp application() {
    return new GarminDataIngestionApp(tokenService, httpClient, new FileWriter());
  }

  private String[] arguments(Path outputDirectory) {
    return arguments(outputDirectory, ENDPOINT, DATE_PARAMETER + "=" + DATE);
  }

  private String[] arguments(Path outputDirectory, String endpoint, String... queryParameters) {
    List<String> args = new ArrayList<>(List.of(
        "--date", DATE, "--output-dir", outputDirectory.toString(), "--endpoint", endpoint));
    for (String parameter : queryParameters) {
      args.add("--query-param");
      args.add(parameter);
    }
    return args.toArray(String[]::new);
  }

  private Path archive() {
    return directory.resolve(DATE + ".json");
  }
}
