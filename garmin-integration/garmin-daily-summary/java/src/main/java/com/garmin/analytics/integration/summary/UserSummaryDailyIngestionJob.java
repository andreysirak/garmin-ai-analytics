package com.garmin.analytics.integration.summary;

import com.garmin.analytics.integration.common.client.GarminClient;
import com.garmin.analytics.integration.common.exception.GarminApiException;
import com.garmin.analytics.integration.common.exception.IngestionExecutionException;
import com.garmin.analytics.integration.common.exception.InvalidResponseException;
import com.garmin.analytics.integration.common.io.FileWriter;
import com.garmin.analytics.integration.common.util.JsonValidationUtil;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/** Fetches and archives one Garmin daily summary response after checking JSON syntax. */
public final class UserSummaryDailyIngestionJob {

  private static final String ENDPOINT = "/usersummary-service/usersummary/daily";

  private final GarminClient client;
  private final FileWriter writer;

  public UserSummaryDailyIngestionJob(GarminClient client, FileWriter writer) {
    this.client = Objects.requireNonNull(client);
    this.writer = Objects.requireNonNull(writer);
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    DailySummaryApplication.launch(args);
  }

  public void run(LocalDate date, Path outputDirectory)
      throws GarminApiException, InvalidResponseException, IngestionExecutionException,
          InterruptedException {
    String rawJson = client.callWithQueryParameters(ENDPOINT, Map.of("calendarDate", date.toString()));
    JsonValidationUtil.validate(rawJson);
    archive(outputDirectory, date, rawJson);
  }

  private void archive(Path outputDirectory, LocalDate date, String rawJson)
      throws IngestionExecutionException {
    Path destination = outputDirectory.resolve(date + ".json");
    try {
      writer.write(destination, rawJson);
    } catch (IOException exception) {
      throw new IngestionExecutionException("Could not archive Garmin daily summary response",
          exception);
    }
  }
}
