package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/** A response that is not a single syntactically valid JSON value. */
public final class InvalidResponseException extends IOException {

  public InvalidResponseException(String message) {
    super(message);
  }

  public InvalidResponseException(String message, Throwable cause) {
    super(message, cause);
  }
}
