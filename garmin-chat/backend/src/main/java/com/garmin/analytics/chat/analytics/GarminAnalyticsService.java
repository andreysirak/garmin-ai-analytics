package com.garmin.analytics.chat.analytics;

import com.garmin.analytics.chat.model.PeriodSelection;
import com.garmin.analytics.chat.model.QueryEvidence;
import com.garmin.analytics.chat.model.QueryIntent.Aggregation;
import java.time.LocalDate;
import java.util.List;

/** Validates and executes Garmin scalar analytics requests. */
public interface GarminAnalyticsService {

  LocalDate referenceDate();

  QueryEvidence aggregate(List<String> fields, Aggregation aggregation, PeriodSelection period,
      LocalDate referenceDate);
}
