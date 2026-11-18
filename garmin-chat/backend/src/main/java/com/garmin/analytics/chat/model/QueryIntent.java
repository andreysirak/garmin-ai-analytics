package com.garmin.analytics.chat.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** An executable, validated scalar query; profile selection remains server-side. */
public record QueryIntent(List<Metric> metrics, Aggregation aggregation, DateRange range) {

  public QueryIntent {
    metrics = List.copyOf(metrics);
    Objects.requireNonNull(aggregation);
    Objects.requireNonNull(range);
    if (metrics.isEmpty() || new HashSet<>(metrics).size() != metrics.size()
        || metrics.stream().anyMatch(metric -> !metric.supports(aggregation))) {
      throw new IllegalArgumentException("Expected distinct metrics supporting the aggregation");
    }
  }

  public enum Aggregation {
    AVG, SUM, MIN, MAX
  }

  public enum Metric {
    TOTAL_STEPS("totalSteps", "steps", 1, Aggregation.values()),
    RESTING_HEART_RATE("restingHeartRate", "bpm", 1,
        Aggregation.AVG, Aggregation.MIN, Aggregation.MAX),
    ACTIVE_KILOCALORIES("activeKilocalories", "kcal", 1, Aggregation.values()),
    SLEEPING_SECONDS("sleepingSeconds", "hours", 3600, Aggregation.values());

    private final String field;
    private final String unit;
    private final int divisor;
    private final Set<Aggregation> aggregations;

    Metric(String field, String unit, int divisor, Aggregation... aggregations) {
      this.field = field;
      this.unit = unit;
      this.divisor = divisor;
      this.aggregations = Set.of(aggregations);
    }

    public String field() {
      return field;
    }

    public String unit() {
      return unit;
    }

    /** Conversion to the evidence unit will be performed by PostgreSQL, not the model. */
    public int divisor() {
      return divisor;
    }

    public boolean supports(Aggregation aggregation) {
      return aggregations.contains(aggregation);
    }

    public static Optional<Metric> fromField(String field) {
      return Arrays.stream(values()).filter(metric -> metric.field.equals(field)).findFirst();
    }
  }
}
