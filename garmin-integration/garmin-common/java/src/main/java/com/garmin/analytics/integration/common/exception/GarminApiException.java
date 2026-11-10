package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/** Signals an unsuccessful Garmin HTTP response or an I/O failure during API transport. */
public final class GarminApiException extends IOException {

  /**
   * Creates an API failure with a diagnostic message.
   *
   * @param message a failure summary safe for task logs
   */
  public GarminApiException(String message) {
    super(message);
  }

  /**
   * Creates an API failure while preserving its underlying cause.
   *
   * @param message a failure summary safe for task logs
   * @param cause the underlying failure, or {@code null} if unavailable
   */
  public GarminApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
