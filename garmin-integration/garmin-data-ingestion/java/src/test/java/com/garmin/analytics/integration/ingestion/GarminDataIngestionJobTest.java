package com.garmin.analytics.integration.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GarminDataIngestionJobTest {

  @TempDir Path directory;

  @Test
  void helpSucceedsWithoutRuntimeConfiguration() throws Exception {
    ProcessResult result = run(List.of("--help"), null);

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("--endpoint /path/to/resource"));
    assertTrue(result.output().contains("--query-param key=value"));
    assertFalse(result.output().contains("--api-base-url"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"date", "output-dir", "endpoint"})
  void requiresCallerSuppliedArguments(String option) throws Exception {
    List<String> args = arguments();
    int index = args.indexOf("--" + option);
    args.remove(index + 1);
    args.remove(index);

    ProcessResult result = run(args, null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("Invalid ingestion arguments"));
  }

  @Test
  void validArgumentsNeedNoBaseUrlAndTokenFailurePropagatesThroughMain() throws Exception {
    ProcessResult result = run(arguments(), null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains(
        "IngestionExecutionException: Could not read GARMIN_TOKEN_FILE"));
    assertFalse(result.output().contains("Invalid ingestion arguments"));
    assertEquals(1, result.output().lines()
        .filter(line -> line.startsWith("Exception in thread \"main\"")).count());
  }

  @Test
  void rejectsTheRemovedBaseUrlOptionWithoutExposingItsValue() throws Exception {
    List<String> args = arguments();
    args.addAll(List.of("--api-base-url", "https://private-credential-value.example"));

    ProcessResult result = run(args, null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("Invalid ingestion arguments"));
    assertFalse(result.output().contains("private-credential-value"));
  }

  @Test
  void malformedTokenFailsBeforeNetworkingWithoutExposingItsValue() throws Exception {
    ProcessResult result = run(arguments(), "private-credential-value\nsecond-line");

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("Could not read GARMIN_TOKEN_FILE"));
    assertFalse(result.output().contains("private-credential-value"));
  }

  @Test
  void invalidCliValuesAreNotExposedByOrdinaryStackTraces() throws Exception {
    ProcessResult result = run(List.of("--private-token", "private-credential-value"), null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("Invalid ingestion arguments"));
    assertFalse(result.output().contains("private-token"));
    assertFalse(result.output().contains("private-credential-value"));
  }

  @Test
  void repeatedQueryParametersAreParsedBeforeReadingTheToken() throws Exception {
    List<String> args = arguments();
    args.addAll(List.of("--query-param", "from=2026-10-01",
        "--query-param", "limit=25", "--query-param", "empty="));

    ProcessResult result = run(args, null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("Could not read GARMIN_TOKEN_FILE"));
    assertFalse(result.output().contains("Invalid ingestion arguments"));
  }

  @Test
  void malformedQueryParameterFailsWithoutExposingItsValue() throws Exception {
    List<String> args = arguments();
    args.addAll(List.of("--query-param", "private-credential-value"));

    ProcessResult result = run(args, null);

    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("--query-param must use key=value"));
    assertFalse(result.output().contains("private-credential-value"));
  }

  private List<String> arguments() {
    return new ArrayList<>(List.of(
        "--date", "2026-11-07", "--output-dir", directory.toString(),
        "--endpoint", "/caller/resource"));
  }

  private ProcessResult run(List<String> args, String tokenContents) throws Exception {
    String classpath = System.getProperty(
        "surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", classpath, GarminDataIngestionJob.class.getName());
    builder.command().addAll(args);
    builder.environment().remove("GARMIN_TOKEN_FILE");
    if (tokenContents != null) {
      Path tokenFile = directory.resolve("token");
      Files.writeString(tokenFile, tokenContents);
      builder.environment().put("GARMIN_TOKEN_FILE", tokenFile.toString());
    }
    builder.redirectErrorStream(true);
    Process process = builder.start();
    if (!process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("Garmin ingestion CLI did not finish in time");
    }
    return new ProcessResult(process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private record ProcessResult(int exitCode, String output) {}
}
