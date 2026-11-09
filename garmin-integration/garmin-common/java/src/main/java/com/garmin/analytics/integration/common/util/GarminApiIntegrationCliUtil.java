package com.garmin.analytics.integration.common.util;

import com.garmin.analytics.integration.common.cli.GarminApiIntegrationOptions;
import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

/** Parses and validates shared ingestion CLI arguments. */
public final class GarminApiIntegrationCliUtil {

  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private GarminApiIntegrationCliUtil() {}

  public static GarminApiIntegrationOptions process(String[] args)
      throws IngestionExecutionException {
    try {
      validateArguments(args);
      return validateOptions(getOptions(args));
    } catch (ParseException exception) {
      // Commons CLI messages can include raw argument values. Keep the cause for debugging only.
      throw new IngestionExecutionException(
          "Invalid ingestion arguments; expected --date, --api-base-url and --output-dir",
          exception);
    } catch (IllegalArgumentException exception) {
      String message = exception instanceof InvalidPathException
          ? "Invalid --output-dir" : exception.getMessage();
      throw new IngestionExecutionException(message, exception);
    }
  }

  private static void validateArguments(String[] args) {
    if (args == null || Arrays.stream(args).anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("Ingestion arguments must not contain null values");
    }
  }

  private static CommandLine getOptions(String[] args) throws ParseException {
    return new DefaultParser(false).parse(createOptions(), args);
  }

  private static GarminApiIntegrationOptions validateOptions(CommandLine options) {
    if (!options.getArgList().isEmpty()) {
      throw new IllegalArgumentException("Unexpected positional ingestion arguments");
    }
    String dateValue = requireValue(options, "date");
    String baseUriValue = requireValue(options, "api-base-url");
    String outputDirectoryValue = requireValue(options, "output-dir");

    LocalDate date = parseDate(dateValue);
    URI baseUri = parseBaseUri(baseUriValue);
    Path outputDirectory = Path.of(outputDirectoryValue);

    return new GarminApiIntegrationOptions(date, baseUri, outputDirectory);
  }

  public static boolean isHelp(String[] args) {
    return args != null && args.length == 1 && "--help".equals(args[0]);
  }

  private static Options createOptions() {
    Options options = new Options();
    options.addOption(Option.builder().longOpt("date").hasArg().required().get());
    options.addOption(Option.builder().longOpt("api-base-url").hasArg().required().get());
    options.addOption(Option.builder().longOpt("output-dir").hasArg().required().get());
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
    GarminClient.validateBaseUri(uri);
    return uri;
  }

  private static String requireValue(CommandLine options, String name) {
    if (options.getOptionCount(name) > 1) {
      throw new IllegalArgumentException("Option may only be specified once: --" + name);
    }
    // Commons CLI enforces presence through required().hasArg(); blank values remain our policy.
    String value = options.getOptionValue(name);
    if (value.isBlank() || value.startsWith("--")) {
      throw new IllegalArgumentException("A value is required for --" + name);
    }
    return value;
  }
}
