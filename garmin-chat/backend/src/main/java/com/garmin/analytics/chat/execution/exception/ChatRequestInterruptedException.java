package com.garmin.analytics.chat.execution.exception;

public class ChatRequestInterruptedException extends RuntimeException {

  public ChatRequestInterruptedException(Throwable cause) {
    super("The chat request was interrupted. Please retry.", cause);
  }
}
