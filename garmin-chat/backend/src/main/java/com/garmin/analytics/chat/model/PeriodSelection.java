package com.garmin.analytics.chat.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Unresolved period supplied by the model. Explicit dates are inclusive ISO calendar dates. */
public record PeriodSelection(
    @JsonProperty(required = true) Type type,
    @JsonProperty(required = true) String startDate,
    @JsonProperty(required = true) String endDate,
    @JsonProperty(required = true) Integer days) {

  public enum Type {
    EXPLICIT, LAST_N_DAYS, LAST_WEEK, THIS_WEEK, LAST_MONTH, THIS_MONTH, YESTERDAY, TODAY
  }
}
