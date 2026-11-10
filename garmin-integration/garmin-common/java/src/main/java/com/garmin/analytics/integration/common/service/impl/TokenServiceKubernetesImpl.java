package com.garmin.analytics.integration.common.service.impl;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.service.TokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and validates a bearer token from a Kubernetes Secret mounted as a UTF-8 file.
 *
 * <p>The path is supplied through {@code GARMIN_TOKEN_FILE}. This implementation reads the mounted
 * file using the filesystem and does not call the Kubernetes API.
 */
public final class TokenServiceKubernetesImpl implements TokenService {

  private final String tokenFile;

  /**
   * Stores the token-file setting for validation when the token is read.
   *
   * @param tokenFile the {@code GARMIN_TOKEN_FILE} path, or {@code null} when unconfigured
   */
  public TokenServiceKubernetesImpl(String tokenFile) {
    this.tokenFile = tokenFile;
  }

  /** {@inheritDoc} */
  @Override
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
