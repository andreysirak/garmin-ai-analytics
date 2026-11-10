package com.garmin.analytics.integration.common.service;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;

/** Supplies a validated bearer token for Garmin API requests. */
public interface TokenService {

  /**
   * Returns a bearer token suitable for an HTTP Authorization header.
   *
   * @return a nonempty ASCII token without whitespace or control characters
   * @throws IngestionExecutionException if the token cannot be loaded or validated
   */
  String readBearerToken() throws IngestionExecutionException;
}
