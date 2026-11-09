package com.garmin.analytics.integration.common.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.util.GarminApiIntegrationCliUtil;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

class IngestionCliUtilTest {

  @Test
  void parsesExistingArgumentsInAnyOrder() throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(new String[] {
        "--output-dir", "./raw", "--api-base-url", "https://example.com", "--date", "2026-11-07"});
    assertEquals(LocalDate.of(2026, 11, 7), options.date());
    assertEquals(Path.of("./raw"), options.outputDirectory());
  }

  @Test
  void rejectsMalformedOptionsWithDedicatedExceptionAndCause() {
    String[][] invalid = {null, {}, {"--date"},
        {"--private-token", "value"},
        {"--date", "2026-11-07", "--date", "2026-11-07"},
        {"--date", "2026-02-30", "--api-base-url", "https://example.com", "--output-dir", "raw"},
        {"--date", "2026-11-07", "--api-base-url", "https://user:private-token@example.com",
            "--output-dir", "raw"}};
    for (String[] args : invalid) {
      IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
          () -> GarminApiIntegrationCliUtil.process(args));
      assertNotNull(failure.getCause());
      assertFalse(failure.getMessage().contains("private-token"));
    }
  }

  @Test
  void delegatesRequiredOptionAndArgumentChecksToCommonsCli() {
    IngestionExecutionException missingOption = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(new String[] {
            "--date", "2026-11-07", "--api-base-url", "https://example.com"}));
    assertInstanceOf(MissingOptionException.class, missingOption.getCause());

    IngestionExecutionException missingArgument = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(new String[] {
            "--date", "2026-11-07", "--api-base-url", "https://example.com", "--output-dir"}));
    assertInstanceOf(MissingArgumentException.class, missingArgument.getCause());
  }

  @Test
  void rejectsDuplicateOptionsInsteadOfSilentlyOverridingTheirValues() {
    String[] valid = {
        "--date", "2026-11-07", "--api-base-url", "https://example.com", "--output-dir", "raw"};
    for (String name : new String[] {"--date", "--api-base-url", "--output-dir"}) {
      String[] args = Arrays.copyOf(valid, valid.length + 2);
      args[valid.length] = name;
      args[valid.length + 1] = "duplicate";

      IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
          () -> GarminApiIntegrationCliUtil.process(args));

      assertTrue(failure.getMessage().contains("Option may only be specified once: " + name));
    }
  }

  @Test
  void preservesCommonsCliCausesWithoutExposingRawParserMessages() {
    String[][] invalid = {
        {"--date", "2026-11-07", "--api-base-url", "https://example.com"},
        {"--private-token", "private-value"},
        {"--da", "2026-11-07", "--api-base-url", "https://example.com", "--output-dir", "raw"}};
    for (String[] args : invalid) {
      IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
          () -> GarminApiIntegrationCliUtil.process(args));

      assertInstanceOf(ParseException.class, failure.getCause());
      assertFalse(failure.getMessage().contains("private-token"));
      assertFalse(failure.getMessage().contains("private-value"));
    }
  }

  @Test
  void rejectsBlankValuesAndUnexpectedPositionalArguments() {
    String[][] invalid = {
        {"--date", "2026-11-07", "--api-base-url", "https://example.com", "--output-dir", " "},
        {"--date", "2026-11-07", "--api-base-url", "https://example.com", "--output-dir", "raw",
            "private-positional-value"},
        {"--date", null, "--api-base-url", "https://example.com", "--output-dir", "raw"}};
    for (String[] args : invalid) {
      IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
          () -> GarminApiIntegrationCliUtil.process(args));
      assertFalse(failure.getMessage().contains("private-positional-value"));
    }
  }

  @Test
  void recognizesHelpWithoutConfiguration() {
    assertTrue(GarminApiIntegrationCliUtil.isHelp(new String[] {"--help"}));
    assertFalse(GarminApiIntegrationCliUtil.isHelp(null));
    assertFalse(GarminApiIntegrationCliUtil.isHelp(new String[] {"--help", "extra"}));
  }
}
