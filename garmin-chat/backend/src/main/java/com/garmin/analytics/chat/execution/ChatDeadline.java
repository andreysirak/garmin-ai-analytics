package com.garmin.analytics.chat.execution;

import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;


public final class ChatDeadline {

  private final long expiresAtNanos;
  private volatile boolean cancelled;

  ChatDeadline(long expiresAtNanos) {
    this.expiresAtNanos = expiresAtNanos;
  }

  long remainingNanos() {
    return expiresAtNanos - System.nanoTime();
  }

  void cancel() {
    cancelled = true;
  }

  public void check() {
    if (remainingNanos() <= 0) {
      throw new ChatRequestTimeoutException(null);
    }
    if (cancelled || Thread.currentThread().isInterrupted()) {
      throw new ChatRequestInterruptedException(null);
    }
  }
}
