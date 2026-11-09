package com.garmin.analytics.integration.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
