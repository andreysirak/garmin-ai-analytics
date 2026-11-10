package com.garmin.analytics.integration.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenServiceTest {

  @TempDir Path directory;

  @Test
  void readsTrimmedTokenFromConfiguredFile() throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, "  synthetic-token\n");
    TokenService service = new TokenService(token.toString());
    assertEquals("synthetic-token", service.readBearerToken());
  }

  @Test
  void rejectsEmptyTokenAndPreservesTheCause() throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, "  ");
    TokenService service = new TokenService(token.toString());

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        service::readBearerToken);
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  @Test
  void validatesMissingConfigurationWhenReading() {
    TokenService service = new TokenService(null);
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        service::readBearerToken);
    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
  }

  @Test
  void preservesMissingFileCauseWithoutExposingThePathInItsMessage() {
    TokenService service = new TokenService(directory.resolve("private-token-path").toString());
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        service::readBearerToken);
    assertInstanceOf(IOException.class, failure.getCause());
    assertEquals("Could not read GARMIN_TOKEN_FILE", failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"synthetic-secret\nsecond-line", "synthetic-secret\u2603"})
  void invalidHeaderCharactersCannotExposeTheTokenInStackTraces(String value) throws Exception {
    Path token = directory.resolve("token");
    Files.writeString(token, value);
    TokenService service = new TokenService(token.toString());

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        service::readBearerToken);
    StringWriter trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));

    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    assertFalse(trace.toString().contains("synthetic-secret"));
  }
}
