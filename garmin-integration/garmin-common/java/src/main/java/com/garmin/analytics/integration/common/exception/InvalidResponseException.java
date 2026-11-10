package com.garmin.analytics.integration.common.exception;

import java.io.IOException;

/**
 * Signals a response that does not contain exactly one complete, syntactically valid JSON value.
 */
public final class InvalidResponseException extends IOException {

  /**
   * Creates a JSON-syntax failure without attaching private parser details.
   *
   * @param message a failure summary that excludes response content
   */
  public InvalidResponseException(String message) {
    super(message);
  }
}
