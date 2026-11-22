package com.garmin.analytics.integration.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.service.TokenService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GarminDataIngestionAppTest {

  private static final String DATE = "2026-11-07";
  private static final String RESPONSE = "{\"totalSteps\":123}";

  @TempDir Path directory;

  private TokenService tokens;
  private HttpClient http;
  private FileWriter writer;
  private HttpResponse<String> response;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    tokens = mock(TokenService.class);
    http = mock(HttpClient.class);
    writer = mock(FileWriter.class);
    response = mock(HttpResponse.class);
    when(tokens.readBearerToken()).thenReturn("synthetic-token");
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(RESPONSE);
    when(http.<String>send(any(), any())).thenReturn(response);
  }

  @Test
  void requiresAllCollaboratorsAtConstruction() {
    assertThrows(NullPointerException.class,
        () -> new GarminDataIngestionApp(null, http, writer));
    assertThrows(NullPointerException.class,
        () -> new GarminDataIngestionApp(tokens, null, writer));
    assertThrows(NullPointerException.class,
        () -> new GarminDataIngestionApp(tokens, http, null));
  }

  @Test
  void passesOriginalResponseToWriterAndMarksAttemptOnlyAfterWriting() throws Exception {
    Path destination = directory.resolve(DATE + ".json");
    when(writer.write(destination, RESPONSE)).thenAnswer(invocation -> {
      assertFalse(Files.exists(directory.resolve("_SUCCESS")));
      return destination;
    });

    application().run(arguments(directory));

    verify(tokens).readBearerToken();
    verify(writer).write(destination, RESPONSE);
    assertTrue(Files.exists(directory.resolve("_SUCCESS")));
    assertEquals(0, Files.size(directory.resolve("_SUCCESS")));
    verify(http).close();
  }

  @Test
  void helpSkipsDependenciesAndClosesHttpClient() throws Exception {
    application().run(new String[] {"--help"});

    verifyNoInteractions(writer);
    verify(tokens, never()).readBearerToken();
    verify(http, never()).send(any(), any());
    verify(http).close();
    assertFalse(Files.exists(directory.resolve("_SUCCESS")));
  }

  @Test
  void rejectsInvalidArgumentsBeforeReadingCredentials() {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> application().run(new String[] {
            "--date", DATE, "--output-dir", directory.toString(),
            "--endpoint", "relative/path"}));

    assertTrue(failure.getMessage().contains("--endpoint"));
    verify(tokens, never()).readBearerToken();
    verify(http, never()).send(any(), any());
    verifyNoInteractions(writer);
    verify(http).close();
  }

  @Test
  void propagatesTokenFailureBeforeRequestOrArchive() throws Exception {
    IOException tokenFailure = new IngestionExecutionException("Synthetic token failure");
    when(tokens.readBearerToken()).thenThrow(tokenFailure);

    IOException actual = assertThrows(IOException.class,
        () -> application().run(arguments(directory)));

    assertSame(tokenFailure, actual);
    verify(http, never()).send(any(), any());
    verifyNoInteractions(writer);
    verify(http).close();
    assertFalse(Files.exists(directory.resolve("_SUCCESS")));
  }

  @Test
  void rejectsMalformedJsonBeforeArchiving() throws Exception {
    when(response.body()).thenReturn("{");

    assertThrows(InvalidResponseException.class,
        () -> application().run(arguments(directory)));

    verifyNoInteractions(writer);
    assertFalse(Files.exists(directory.resolve("_SUCCESS")));
    verify(http).close();
  }

  @Test
  void rejectsNonemptyAttemptDirectoryBeforeCallingWriter() throws Exception {
    Path previous = directory.resolve("previous.json");
    Files.writeString(previous, "preserved");

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> application().run(arguments(directory)));

    assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
    assertEquals("preserved", Files.readString(previous));
    verifyNoInteractions(writer);
    assertFalse(Files.exists(directory.resolve("_SUCCESS")));
    verify(http).close();
  }

  @Test
  void preservesWriterFailureAndDoesNotCreateSuccessMarker() throws Exception {
    IOException diskFailure = new IOException("Synthetic write failure");
    when(writer.write(directory.resolve(DATE + ".json"), RESPONSE)).thenThrow(diskFailure);

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> application().run(arguments(directory)));

    assertSame(diskFailure, failure.getCause());
    assertFalse(Files.exists(directory.resolve("_SUCCESS")));
    verify(http).close();
  }

  @Test
  void restoresInterruptFlagAndClosesHttpClient() throws Exception {
    when(http.<String>send(any(), any()))
        .thenThrow(new InterruptedException("Synthetic interruption"));

    try {
      assertThrows(InterruptedException.class, () -> application().run(arguments(directory)));

      assertTrue(Thread.currentThread().isInterrupted());
      verifyNoInteractions(writer);
      assertFalse(Files.exists(directory.resolve("_SUCCESS")));
      verify(http).close();
    } finally {
      Thread.interrupted();
    }
  }

  private GarminDataIngestionApp application() {
    return new GarminDataIngestionApp(tokens, http, writer);
  }

  private static String[] arguments(Path outputDirectory) {
    return new String[] {
        "--date", DATE, "--output-dir", outputDirectory.toString(),
        "--endpoint", "/caller/resource"};
  }
}
