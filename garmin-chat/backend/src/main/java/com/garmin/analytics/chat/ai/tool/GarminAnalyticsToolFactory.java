package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.execution.ChatDeadline;
import com.garmin.analytics.chat.model.AnalyticsSelection;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class GarminAnalyticsToolFactory {

  private final GarminAnalyticsService analytics;

  public GarminAnalyticsToolFactory(GarminAnalyticsService analytics) {
    this.analytics = analytics;
  }

  public GarminAnalyticsTool create(
      ChatDeadline deadline, LocalDate referenceDate, AnalyticsSelection selection) {
    return new GarminAnalyticsTool(analytics, referenceDate, deadline, selection);
  }
}
