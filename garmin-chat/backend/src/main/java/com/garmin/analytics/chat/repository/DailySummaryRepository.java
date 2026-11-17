package com.garmin.analytics.chat.repository;

import com.garmin.analytics.chat.model.DailySummary;
import com.garmin.analytics.chat.model.MetricAggregation;
import com.garmin.analytics.chat.model.QueryIntent;
import java.time.LocalDate;
import java.util.List;

public interface DailySummaryRepository {

  List<DailySummary> findSummaries(long profileId, LocalDate startDate, LocalDate endDate);

  /** Aggregates only the server-configured profile; intent cannot choose a profile. */
  List<MetricAggregation> aggregate(QueryIntent intent);
}
