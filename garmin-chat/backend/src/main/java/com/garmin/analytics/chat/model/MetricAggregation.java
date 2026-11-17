package com.garmin.analytics.chat.model;

import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import com.garmin.analytics.chat.model.QueryIntent.Metric;
import java.math.BigDecimal;

/** PostgreSQL-calculated value in the catalog unit; null means no numeric readings. */
public record MetricAggregation(
    Metric metric, Aggregation aggregation, BigDecimal value, String unit, DateRange range,
    DataCoverage coverage) {}
