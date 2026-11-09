package com.garmin.analytics.integration.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FailureLoggingUtilTest {

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger("privacy-test");
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setAdditive(false);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
    logger.setAdditive(true);
    appender.stop();
  }

  @Test
  void keepsOriginalCauseButLogsOnlySanitizedStackTraces() throws Exception {
    InvalidResponseException failure = assertThrows(InvalidResponseException.class,
        () -> JsonValidationUtil.validate(
            "{\"private-dynamic-key\":\"private-health-value\""));
    Throwable cause = failure.getCause();
    cause.addSuppressed(new IOException("private-token"));
    FailureLoggingUtil.log(logger, "Ingestion failed", failure);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    String logged = event.getFormattedMessage()
        + ThrowableProxyUtil.asString(event.getThrowableProxy());
    assertTrue(logged.contains("Garmin response JSON error"));
    assertTrue(logged.contains(cause.getClass().getName()));
    assertTrue(logged.contains("FailureLoggingUtilTest.java"));
    assertFalse(logged.contains("private-health-value"));
    assertFalse(logged.contains("private-dynamic-key"));
    assertFalse(logged.contains("private-token"));
    assertSame(cause, failure.getCause());
  }

  @Test
  void sanitizesUncaughtErrorsAsWellAsExceptions() {
    FailureLoggingUtil.log(logger, "Ingestion failed", new AssertionError("private-error-value"));

    assertEquals(1, appender.list.size());
    String logged = ThrowableProxyUtil.asString(appender.list.get(0).getThrowableProxy());
    assertTrue(logged.contains("AssertionError"));
    assertFalse(logged.contains("private-error-value"));
  }

}
