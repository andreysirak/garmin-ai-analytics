package com.garmin.analytics.integration.common.util;

import com.garmin.analytics.integration.common.exception.GarminApiException;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import org.slf4j.Logger;

/** Logs failures once without exposing response values, credentials, paths, or URI details. */
public final class FailureLoggingUtil {

  private FailureLoggingUtil() {}

  public static void log(Logger logger, String context, Throwable failure) {
    logger.error(context, sanitizedTrace(failure, 0));
  }

  private static Throwable sanitizedTrace(Throwable failure, int depth) {
    // Only our dedicated exceptions have messages constructed from safe, controlled context.
    boolean safeMessage = failure instanceof GarminApiException
        || failure instanceof InvalidResponseException
        || failure instanceof IngestionExecutionException;
    Throwable sanitized = new Throwable(failure.getClass().getName()
        + (safeMessage ? ": " + failure.getMessage() : ": details withheld"));
    sanitized.setStackTrace(failure.getStackTrace());
    if (failure.getCause() != null && depth < 8) {
      sanitized.initCause(sanitizedTrace(failure.getCause(), depth + 1));
    }
    // Suppressed exceptions may also contain sensitive messages; never copy them.
    return sanitized;
  }
}
