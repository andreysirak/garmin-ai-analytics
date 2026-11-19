package com.garmin.analytics.chat.analytics.exception;

/** A safe explanation of why analytical arguments were rejected. */
public class InvalidAnalyticsRequest extends RuntimeException {

  public InvalidAnalyticsRequest(String message) {
    super(message);
  }

  public InvalidAnalyticsRequest(String message, Throwable cause) {
    super(message, cause);
  }
}
