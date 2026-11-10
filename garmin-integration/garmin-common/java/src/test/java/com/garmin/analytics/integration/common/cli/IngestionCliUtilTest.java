package com.garmin.analytics.integration.common.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.util.GarminApiIntegrationCliUtil;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IngestionCliUtilTest {

  @Test
  void parsesAllArgumentsIntoReadyToUseOptions() throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(new String[] {
        "--endpoint", "/caller/resource", "--output-dir", "./raw",
        "--query-param", "callerKey=value", "--query-param", "limit=25",
        "--date", "2026-11-07"});

    assertEquals(LocalDate.of(2026, 11, 7), options.date());
    assertEquals(Path.of("./raw"), options.outputDirectory());
    assertEquals("/caller/resource", options.endpoint());
    assertEquals(Map.of("callerKey", "value", "limit", "25"), options.queryParameters());
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "output-dir", "endpoint"})
  void rejectsMissingRequiredOptions(String option) {
    List<String> args = new ArrayList<>(List.of(arguments()));
    int index = args.indexOf("--" + option);
    args.remove(index + 1);
    args.remove(index);

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(args.toArray(String[]::new)));

    assertTrue(failure.getMessage().contains("Invalid ingestion arguments"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "output-dir", "endpoint"})
  void rejectsDuplicateOptions(String option) {
    String[] args = Arrays.copyOf(arguments(), arguments().length + 2);
    args[args.length - 2] = "--" + option;
    args[args.length - 1] = "duplicate";

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(args));

    assertEquals("Option may only be specified once: --" + option, failure.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "output-dir", "endpoint"})
  void rejectsBlankValues(String option) {
    assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withValue(option, " ")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/caller/resource", "/another-service/v9/data", "/encoded%20path"})
  void acceptsCallerSelectedEndpointsWithoutAWhitelist(String endpoint) throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(
        withValue("endpoint", endpoint));

    assertEquals(endpoint, options.endpoint());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "relative/path", "//other-host/path", "https://other-host/path",
      "/path?token=private-value", "/path#private-value", "/invalid path"})
  void rejectsMalformedEndpointPathsWithoutExposingTheirValues(String endpoint) {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withValue("endpoint", endpoint)));

    assertTrue(failure.getMessage().contains("--endpoint"));
    assertFalse(stackTrace(failure).contains("private-value"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "calendarDate", "filter[]", "requested date"})
  void acceptsCallerSelectedQueryParameterNames(String parameter) throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(
        withQueryParameters(parameter + "=literal-value"));

    assertEquals(Map.of(parameter, "literal-value"), options.queryParameters());
  }

  @Test
  void acceptsNoQueryParameters() throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(arguments());

    assertTrue(options.queryParameters().isEmpty());
  }

  @Test
  void acceptsManyParametersWithOpaqueValues() throws Exception {
    GarminApiIntegrationOptions options = GarminApiIntegrationCliUtil.process(withQueryParameters(
        "date=not-a-date", "limit=25", "offset=-1", "label=café & /",
        "empty=", "expression=a=b=c"));

    assertEquals(Map.of("date", "not-a-date", "limit", "25", "offset", "-1",
        "label", "café & /", "empty", "", "expression", "a=b=c"), options.queryParameters());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", " ", "missing-equals-private-value", "=private-value", " =private-value"})
  void rejectsMalformedQueryParametersWithoutExposingTheirValues(String entry) {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withQueryParameters(entry)));

    assertTrue(failure.getMessage().contains("key=value"));
    assertFalse(stackTrace(failure).contains("private-value"));
  }

  @Test
  void rejectsDuplicateQueryKeysWithoutExposingTheirNamesOrValues() {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withQueryParameters(
            "private-key=private-value", "private-key=another-value")));

    assertTrue(failure.getMessage().contains("only be specified once"));
    assertFalse(stackTrace(failure).contains("private-key"));
    assertFalse(stackTrace(failure).contains("private-value"));
  }

  @Test
  void queryParameterOptionRequiresAnArgumentWhenPresent() {
    String[] args = Arrays.copyOf(arguments(), arguments().length + 1);
    args[args.length - 1] = "--query-param";

    assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(args));
  }

  @Test
  void rejectsInvalidCalendarDates() {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withValue("date", "2026-02-30")));

    assertTrue(failure.getMessage().contains("valid calendar date"));
    assertNull(failure.getCause());
  }

  @Test
  void rejectsNullArgumentsBeforeParsing() {
    assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(null));
    assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(withValue("endpoint", null)));
  }

  @Test
  void rejectsUnknownOptionsWithoutExposingRawParserMessages() {
    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(new String[] {
            "--private-token", "private-value"}));

    assertFalse(stackTrace(failure).contains("private-token"));
    assertFalse(stackTrace(failure).contains("private-value"));
  }

  @Test
  void rejectsPositionalArguments() {
    String[] args = Arrays.copyOf(arguments(), arguments().length + 1);
    args[args.length - 1] = "private-value";

    IngestionExecutionException failure = assertThrows(IngestionExecutionException.class,
        () -> GarminApiIntegrationCliUtil.process(args));

    assertEquals("Unexpected positional ingestion arguments", failure.getMessage());
    assertFalse(stackTrace(failure).contains("private-value"));
  }

  @Test
  void recognizesHelpWithoutConfiguration() {
    assertTrue(GarminApiIntegrationCliUtil.isHelp(new String[] {"--help"}));
    assertFalse(GarminApiIntegrationCliUtil.isHelp(null));
    assertFalse(GarminApiIntegrationCliUtil.isHelp(new String[] {"--help", "extra"}));
  }

  private static String[] arguments() {
    return new String[] {
        "--date", "2026-11-07",
        "--output-dir", "raw", "--endpoint", "/caller/resource"};
  }

  private static String[] withQueryParameters(String... entries) {
    List<String> args = new ArrayList<>(List.of(arguments()));
    for (String entry : entries) {
      args.add("--query-param");
      args.add(entry);
    }
    return args.toArray(String[]::new);
  }

  private static String[] withValue(String option, String value) {
    String[] args = arguments();
    args[Arrays.asList(args).indexOf("--" + option) + 1] = value;
    return args;
  }

  private static String stackTrace(Throwable failure) {
    StringWriter trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));
    return trace.toString();
  }
}
