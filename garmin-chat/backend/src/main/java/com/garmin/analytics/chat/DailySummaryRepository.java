package com.garmin.analytics.chat;

import java.time.LocalDate;
import java.util.List;

public interface DailySummaryRepository {

  List<DailySummary> findSummaries(long profileId, LocalDate startDate, LocalDate endDate);
}
