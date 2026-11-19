package com.garmin.analytics.chat.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.garmin.analytics.chat.ai.ModelTransport;
import com.garmin.analytics.chat.config.ChatExecutionProperties;
import com.garmin.analytics.chat.execution.exception.ChatRequestInterruptedException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class ChatRequestExecutorTest {

  @Test
  void interruptingTheCallerCancelsTheJdkExchangeAndPreservesInterruptStatus() throws Exception {
    var requests = new ChatRequestExecutor(new ChatExecutionProperties(Duration.ofSeconds(10)));
    var exchange = new CompletableFuture<HttpResponse<InputStream>>();
    var started = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    exchange.whenComplete((response, error) -> cancelled.countDown());
    var http = mock(HttpClient.class);
    when(http.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
        .thenAnswer(invocation -> {
          started.countDown();
          return exchange;
        });
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(20));
    var client = RestClient.builder().requestFactory(factory).build();
    var caller = new FutureTask<Void>(() -> {
      assertThatThrownBy(() -> requests.execute(deadline -> ModelTransport.call(() ->
          client.get().uri("http://synthetic.invalid").retrieve().body(String.class))))
          .isInstanceOf(ChatRequestInterruptedException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      return null;
    });
    var thread = Thread.startVirtualThread(caller);
    try {
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      thread.interrupt();
    }

    caller.get(5, TimeUnit.SECONDS);
    assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(exchange.isCancelled()).isTrue();
  }

  @Test
  void cancellationRemainsVisibleToAWorkerThatConsumedTheInterrupt() throws Exception {
    var requests = new ChatRequestExecutor(new ChatExecutionProperties(Duration.ofSeconds(10)));
    var started = new CountDownLatch(1);
    var observedCancellation = new CompletableFuture<Throwable>();
    var caller = new FutureTask<>(() -> requests.execute(deadline -> {
      started.countDown();
      try {
        new CountDownLatch(1).await(5, TimeUnit.SECONDS);
      } catch (InterruptedException consumed) {
        // Simulate blocking work that returns normally after consuming the interrupt.
      }
      try {
        deadline.check();
        observedCancellation.complete(null);
      } catch (ChatRequestInterruptedException cancelled) {
        observedCancellation.complete(cancelled);
      }
      return "late result";
    }));
    var thread = Thread.startVirtualThread(caller);
    try {
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      thread.interrupt();
    }

    assertThatThrownBy(() -> caller.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(ChatRequestInterruptedException.class);
    assertThat(observedCancellation.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ChatRequestInterruptedException.class);
  }
}
