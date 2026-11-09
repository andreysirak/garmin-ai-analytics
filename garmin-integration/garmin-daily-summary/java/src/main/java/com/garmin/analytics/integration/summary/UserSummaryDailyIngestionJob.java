package com.garmin.analytics.integration.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.io.JsonFileWriter;
import com.garmin.analytics.integration.common.util.JsonFailureDiagnosticsUtil;
import com.garmin.analytics.integration.summary.response.DailySummaryResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Standalone command-line application for Garmin daily summary ingestion. */
public final class UserSummaryDailyIngestionJob {

  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final String ENDPOINT = "/usersummary-service/usersummary/daily";

  private UserSummaryDailyIngestionJob() {}

  public static void main(String[] args) {
    try {
      if (isHelp(args)) {
        printUsage();
        return;
      }

      Map<String, String> options = parseOptions(args);
      LocalDate date = parseDate(options.get("--date"));
      URI baseUri = parseBaseUri(options.get("--api-base-url"));
      Path outputDirectory = Path.of(requireValue(options, "--output-dir"));
      String token = readBearerToken();

      ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
      HttpClient httpClient =
          HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
      GarminClient garminClient = new GarminClient(httpClient, baseUri, token);
      String rawJson =
          garminClient.get(ENDPOINT, Map.of("calendarDate", date.toString()));
      DailySummaryResponse summary = parseResponse(objectMapper, rawJson);
      if (summary.calendarDate() == null
          || !date.equals(summary.calendarDate())) {
        throw new IOException(
            "Garmin daily summary response has a missing or mismatched calendarDate");
      }

      Path destination =
          outputDirectory.resolve("daily-summary").resolve(date + ".json");
      new JsonFileWriter().write(destination, rawJson);
      System.out.println("Ingested Garmin daily summary for " + date);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      fail("Daily summary ingestion was interrupted");
    } catch (JsonProcessingException exception) {
      fail("Garmin daily summary response JSON error: " + JsonFailureDiagnosticsUtil.describe(exception));
    } catch (IOException | IllegalArgumentException exception) {
      fail("Daily summary ingestion failed: " + exception.getMessage());
    }
  }

  private static DailySummaryResponse parseResponse(ObjectMapper mapper, String rawJson)
      throws JsonProcessingException {
    DailySummaryResponse response = mapper.readValue(rawJson, DailySummaryResponse.class);
    if (response == null) {
      throw new IllegalArgumentException("Garmin daily summary response was empty");
    }
    return response;
  }

  private static boolean isHelp(String[] args) {
    return args != null && args.length == 1 && "--help".equals(args[0]);
  }

  private static Map<String, String> parseOptions(String[] args) {
    if (args == null || args.length == 0 || args.length % 2 != 0) {
      throw new IllegalArgumentException(usage());
    }
    Map<String, String> options = new HashMap<>();
    for (int index = 0; index < args.length; index += 2) {
      String name = args[index];
      if (!"--date".equals(name)
          && !"--api-base-url".equals(name)
          && !"--output-dir".equals(name)) {
        throw new IllegalArgumentException("Unknown option: " + name + "\n" + usage());
      }
      String value = args[index + 1];
      if (value == null || value.isBlank() || value.startsWith("--")) {
        throw new IllegalArgumentException("A value is required for " + name);
      }
      if (options.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Option may only be specified once: " + name);
      }
    }
    requireValue(options, "--date");
    requireValue(options, "--api-base-url");
    requireValue(options, "--output-dir");
    return options;
  }

  private static LocalDate parseDate(String value) {
    if (value == null || !ISO_DATE.matcher(value).matches()) {
      throw new IllegalArgumentException("--date must use YYYY-MM-DD format");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("--date must be a valid calendar date", exception);
    }
  }

  private static URI parseBaseUri(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("--api-base-url must be a valid HTTP(S) URL", exception);
    }
    if (!("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("--api-base-url must be a valid HTTP(S) URL");
    }
    return uri;
  }

  private static String readBearerToken() throws IOException {
    String tokenFile = System.getenv("GARMIN_TOKEN_FILE");
    if (tokenFile == null || tokenFile.isBlank()) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE must name the bearer-token file");
    }
    String token = Files.readString(Path.of(tokenFile.trim()), StandardCharsets.UTF_8).trim();
    if (token.isEmpty()) {
      throw new IllegalArgumentException("GARMIN_TOKEN_FILE does not contain a bearer token");
    }
    return token;
  }

  private static String requireValue(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Required option is missing: " + name + "\n" + usage());
    }
    return value;
  }

  private static void fail(String message) {
    System.err.println(message);
    System.exit(1);
  }

  private static void printUsage() {
    System.out.println(usage());
  }

  private static String usage() {
    return """
        Usage: java -jar garmin-daily-summary.jar \\
          --date YYYY-MM-DD \\
          --api-base-url https://personal.connectapi.garmin.com \\
          --output-dir ./data/raw/garmin
        Set GARMIN_TOKEN_FILE to a file containing an existing Garmin bearer token.
        """;
  }
}
