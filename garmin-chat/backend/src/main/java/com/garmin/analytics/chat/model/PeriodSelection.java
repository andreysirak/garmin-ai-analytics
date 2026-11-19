package com.garmin.analytics.chat.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Unresolved period supplied by the model. Explicit dates are inclusive ISO calendar dates. */
public record PeriodSelection(
    @JsonProperty(required = true) Type type,
    String startDate,
    String endDate,
    Integer days) {

  /** Omitted fields have neutral values; semantic validation still rejects conflicting values. */
  public PeriodSelection {
    startDate = startDate == null ? "" : startDate;
    endDate = endDate == null ? "" : endDate;
    days = days == null ? 0 : days;
  }

  public enum Type {
    EXPLICIT, LAST_N_DAYS, LAST_WEEK, THIS_WEEK, LAST_MONTH, THIS_MONTH, YESTERDAY, TODAY
  }
}
