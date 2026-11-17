package com.garmin.analytics.chat.model;

import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import java.math.BigDecimal;
import java.util.List;

/** The complete explanation input; no profile identifiers or raw daily payloads. */
public record QueryEvidence(
    String question, Aggregation aggregation, DateRange dateRange, List<MetricValue> metrics) {

  public QueryEvidence {
    metrics = List.copyOf(metrics);
  }

  public record MetricValue(String metric, BigDecimal value, String unit, DataCoverage coverage) {}
}
