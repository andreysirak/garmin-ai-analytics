package com.garmin.analytics.chat.execution.exception;

/** A deadline expired in the chat request or the underlying model transport. */
public class ChatRequestTimeoutException extends RuntimeException {

  public ChatRequestTimeoutException(Throwable cause) {
    super("The local model took too long to respond. Please choose a shorter period "
        + "or retry when the model is less busy.", cause);
  }
}
