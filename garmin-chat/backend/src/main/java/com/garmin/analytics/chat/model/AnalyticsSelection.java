package com.garmin.analytics.chat.model;

import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import java.util.List;

/** Parameters selected for the single supported scalar analytics tool. */
public record AnalyticsSelection(
    List<String> metrics, Aggregation aggregation, PeriodSelection period) {

  public AnalyticsSelection {
    metrics = metrics == null ? null : List.copyOf(metrics);
  }
}
