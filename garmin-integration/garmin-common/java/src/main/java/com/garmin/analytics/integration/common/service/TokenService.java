package com.garmin.analytics.integration.common.service;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads and validates a Kubernetes-mounted bearer-token file. */
public final class TokenService {

  private final String tokenFile;

  public TokenService(String tokenFile) {
    this.tokenFile = tokenFile;
  }

  public String readBearerToken() throws IngestionExecutionException {
    try {
      return readTokenFile();
    } catch (IOException | IllegalArgumentException exception) {
      throw new IngestionExecutionException("Could not read GARMIN_TOKEN_FILE", exception);
    }
  }

  private String readTokenFile() throws IOException {
    if (tokenFile == null || tokenFile.isBlank()) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE must name the bearer-token file");
    }
    String token = Files.readString(Path.of(tokenFile.trim()), StandardCharsets.UTF_8).trim();
    if (token.isEmpty()) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE does not contain a bearer token");
    }
    return token;
  }
}
