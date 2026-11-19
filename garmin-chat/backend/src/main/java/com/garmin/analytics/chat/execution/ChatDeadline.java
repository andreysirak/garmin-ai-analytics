package com.garmin.analytics.chat.execution;

import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;


public final class ChatDeadline {

  private final long expiresAtNanos;
  private volatile boolean cancelled;
  private final AtomicInteger modelCalls = new AtomicInteger();

  ChatDeadline(long expiresAtNanos) {
    this.expiresAtNanos = expiresAtNanos;
  }

  long remainingNanos() {
    return expiresAtNanos - System.nanoTime();
  }

  void cancel() {
    cancelled = true;
  }

  public int startModelCall() {
    check();
    return modelCalls.incrementAndGet();
  }

  int modelCalls() {
    return modelCalls.get();
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
