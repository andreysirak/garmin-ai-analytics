package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/** Signals an ingestion configuration, token-file, or archive operation that could not complete. */
public final class IngestionExecutionException extends IOException {

  /**
   * Creates an ingestion failure with a diagnostic message.
   *
   * @param message a failure summary safe for task logs
   */
  public IngestionExecutionException(String message) {
    super(message);
  }

  /**
   * Creates an ingestion failure while preserving its underlying cause.
   *
   * @param message a failure summary safe for task logs
   * @param cause the underlying failure, or {@code null} if unavailable
   */
  public IngestionExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
