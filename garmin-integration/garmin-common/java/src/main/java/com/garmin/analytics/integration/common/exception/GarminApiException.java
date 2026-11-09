package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/** A failed HTTP response or transport failure. */
public final class GarminApiException extends IOException {

  public GarminApiException(String message) {
    super(message);
  }

  public GarminApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
