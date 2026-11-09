package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/** A configuration or archive operation that could not complete. */
public final class IngestionExecutionException extends IOException {

  public IngestionExecutionException(String message) {
    super(message);
  }

  public IngestionExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
