package com.garmin.analytics.chat.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mutually exclusive day counts for one metric; zeroDays is a subset of daysWithData. */
public record DataCoverage(
    int expectedDays, int missingSummaryDays, int missingMetricDays, int nullMetricDays,
    int invalidTypeDays, int daysWithData, int zeroDays) {

  public DataCoverage {
    if (expectedDays < 1 || missingSummaryDays < 0 || missingMetricDays < 0 || nullMetricDays < 0
        || invalidTypeDays < 0 || daysWithData < 0 || zeroDays < 0 || zeroDays > daysWithData
        || (long) missingSummaryDays + missingMetricDays + nullMetricDays + invalidTypeDays
            + daysWithData != expectedDays) {
      throw new IllegalArgumentException("Coverage must partition the expected days exactly");
    }
  }

  @JsonProperty
  public int summaryDays() {
    return expectedDays - missingSummaryDays;
  }

  /** Missing excludes invalid types, which are reported separately. */
  @JsonProperty
  public int missingDays() {
    return missingSummaryDays + missingMetricDays + nullMetricDays;
  }

  @JsonProperty
  public boolean complete() {
    return daysWithData == expectedDays;
  }
}
