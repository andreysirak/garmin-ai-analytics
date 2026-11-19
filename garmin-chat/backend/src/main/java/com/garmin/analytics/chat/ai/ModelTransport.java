package com.garmin.analytics.chat.ai;

import com.garmin.analytics.chat.ai.exception.ChatModelUnavailableException;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientException;

/** Translate failures at the provider boundary, where Spring AI's transport types are known. */
public final class ModelTransport {

  private ModelTransport() {}

  public static <T> T call(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RestClientException | TransientAiException | NonTransientAiException failure) {
      for (var cause = (Throwable) failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
            || cause instanceof TimeoutException) {
          throw new ChatRequestTimeoutException(failure);
        }
        if (cause instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new ChatRequestInterruptedException(failure);
        }
      }
      throw new ChatModelUnavailableException(failure);
    }
  }
}
