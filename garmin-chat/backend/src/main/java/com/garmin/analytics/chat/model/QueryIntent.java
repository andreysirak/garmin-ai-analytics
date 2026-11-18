package com.garmin.analytics.chat.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    TOTAL_STEPS("totalSteps", "daily step count", "steps", "steps, daily steps",
        "Cannot measure work productivity, walking speed or distance.", 1, Aggregation.values()),
    RESTING_HEART_RATE("restingHeartRate", "daily resting heart rate", "bpm",
        "resting pulse, resting HR", "Distinct from the rolling seven-day resting-heart-rate "
            + "average. Cannot establish health status.", 1,
        Aggregation.AVG, Aggregation.MIN, Aggregation.MAX),
    ACTIVE_KILOCALORIES("activeKilocalories", "daily active energy", "kcal",
        "active calories, active energy expenditure",
        "Cannot answer total calories or food intake.", 1,
        Aggregation.values()),
    SLEEPING_SECONDS("sleepingSeconds", "recorded daily-summary sleep duration", "hours",
        "recorded daily sleep, daily-summary sleep duration",
        "Clipped to calendar-day windows. Cannot represent primary overnight sleep duration, "
            + "sleep quality, stages or scores.", 3600, Aggregation.values());

    private final String field;
    private final String meaning;
    private final String unit;
    private final String synonyms;
    private final String limitations;
    private final int divisor;
    private final Set<Aggregation> aggregations;

    Metric(String field, String meaning, String unit, String synonyms, String limitations,
        int divisor, Aggregation... aggregations) {
      this.field = field;
      this.meaning = meaning;
      this.unit = unit;
      this.synonyms = synonyms;
      this.limitations = limitations;
      this.divisor = divisor;
      this.aggregations = Set.of(aggregations);
    }

    public String meaning() {
      return meaning;
    }

    public String supportedAggregations() {
      return Arrays.stream(Aggregation.values()).filter(this::supports).map(Enum::name)
          .collect(Collectors.joining(", "));
    }

    public static String catalog() {
      return Arrays.stream(values()).map(metric -> "- %s: %s (%s); synonyms: %s; allowed: %s. %s"
          .formatted(metric.field, metric.meaning, metric.unit, metric.synonyms,
              metric.supportedAggregations(), metric.limitations))
          .collect(Collectors.joining("\n"));
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
