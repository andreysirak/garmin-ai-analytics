package com.garmin.analytics.chat.execution;

import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import com.garmin.analytics.chat.execution.exception.ChatRequestTimeoutException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ChatRequestExecutor {

  private static final Logger log = LoggerFactory.getLogger(ChatRequestExecutor.class);
  private static final String REQUEST_ID = "chatRequestId";

  private final long timeoutNanos;

  public ChatRequestExecutor(ChatExecutionProperties properties) {
    this.timeoutNanos = properties.timeout().toNanos();
  }

  public <T> T execute(Function<ChatDeadline, T> conversation) {
    long started = System.nanoTime();
    var deadline = new ChatDeadline(started + timeoutNanos);
    String requestId = UUID.randomUUID().toString();
    String previousId = MDC.get(REQUEST_ID);
    MDC.put(REQUEST_ID, requestId);
    log.info("chat_started");
    try {
      deadline.check();
      // The synchronous model and JDBC calls can outlast the caller's remaining budget.
      var task = new FutureTask<>(() -> {
        try (var correlation = MDC.putCloseable(REQUEST_ID, requestId)) {
          deadline.check();
          T result = conversation.apply(deadline);
          deadline.check();
          return result;
        }
      });
      Thread.startVirtualThread(task);
      T result = await(task, deadline);
      log.info("chat_completed model_calls={} elapsed_ms={}",
          deadline.modelCalls(), elapsedMillis(started));
      return result;
    } catch (RuntimeException failure) {
      log.warn("chat_failed error_type={} model_calls={} elapsed_ms={}",
          failure.getClass().getSimpleName(), deadline.modelCalls(), elapsedMillis(started));
      throw failure;
    } finally {
      if (previousId == null) {
        MDC.remove(REQUEST_ID);
      } else {
        MDC.put(REQUEST_ID, previousId);
      }
    }
  }

  private static <T> T await(FutureTask<T> task, ChatDeadline deadline) {
    try {
      return task.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException expired) {
      deadline.cancel();
      task.cancel(true);
      throw new ChatRequestTimeoutException(expired);
    } catch (InterruptedException interrupted) {
      deadline.cancel();
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new ChatRequestInterruptedException(interrupted);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (failure.getCause() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Unexpected chat worker failure", failure);
    }
  }

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }
}
