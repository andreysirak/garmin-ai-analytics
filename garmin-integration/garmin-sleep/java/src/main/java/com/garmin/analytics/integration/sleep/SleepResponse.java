package com.garmin.analytics.integration.sleep;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/** Garmin's daily sleep response, with summary values nested under dailySleepDTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepResponse(DailySleepDTO dailySleepDTO) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DailySleepDTO(
      LocalDate calendarDate,
      Long sleepTimeSeconds,
      Long deepSleepSeconds,
      Long lightSleepSeconds,
      Long remSleepSeconds,
      Long awakeSleepSeconds,
      Integer awakeCount,
      Long sleepStartTimestampGMT,
      Long sleepEndTimestampGMT,
      Double avgSleepStress,
      Integer avgHeartRate) {}
}
