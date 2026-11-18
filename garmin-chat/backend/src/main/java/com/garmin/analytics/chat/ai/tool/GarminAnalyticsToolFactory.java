package com.garmin.analytics.chat.ai.tool;

import com.garmin.analytics.chat.analytics.GarminAnalyticsService;
import com.garmin.analytics.chat.execution.ChatDeadline;
import org.springframework.stereotype.Component;

@Component
public class GarminAnalyticsToolFactory {

  private final GarminAnalyticsService analytics;

  public GarminAnalyticsToolFactory(GarminAnalyticsService analytics) {
    this.analytics = analytics;
  }

  public GarminAnalyticsTool create(ChatDeadline deadline) {
    return new GarminAnalyticsTool(analytics, analytics.referenceDate(), deadline);
  }
}
