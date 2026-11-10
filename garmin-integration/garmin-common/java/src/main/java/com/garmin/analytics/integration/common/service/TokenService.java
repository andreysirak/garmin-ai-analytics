package com.garmin.analytics.integration.common.service;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and validates a bearer token from a configured UTF-8 file.
 *
 * <p>The file is typically mounted from a Kubernetes Secret; this service does not call Kubernetes.
 */
public final class TokenService {

  private final String tokenFile;

  /**
   * Stores the token-file setting for validation when the token is read.
   *
   * @param tokenFile the {@code GARMIN_TOKEN_FILE} path, or {@code null} when unconfigured
   */
  public TokenService(String tokenFile) {
    this.tokenFile = tokenFile;
  }

  /**
   * Reads the configured file and returns its validated, trimmed token text.
   *
   * @return a nonempty ASCII bearer token without surrounding whitespace
   * @throws IngestionExecutionException if configuration is missing or invalid, the file cannot
   *     be read, or its contents are not a single nonempty ASCII token
   */
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
    // Invalid header characters can make HttpClient include the token in an error message.
    if (token.chars().anyMatch(character -> character <= 32 || character >= 127)) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE must contain one ASCII bearer token");
    }
    return token;
  }
}
