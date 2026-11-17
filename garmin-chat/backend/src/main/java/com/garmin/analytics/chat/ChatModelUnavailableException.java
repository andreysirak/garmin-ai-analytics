package com.garmin.analytics.chat;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

public class ChatModelUnavailableException extends RuntimeException {

  public ChatModelUnavailableException(Throwable cause) {
    super(detail(cause), cause);
  }

  private static String detail(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return "The local model took too long to respond. Please choose a shorter period "
            + "or retry when the model is less busy.";
      }
    }
    return "The chat model is initializing or unavailable. Please retry shortly. "
        + "Check the model-pull Job and Ollama status if this persists.";
  }
}
