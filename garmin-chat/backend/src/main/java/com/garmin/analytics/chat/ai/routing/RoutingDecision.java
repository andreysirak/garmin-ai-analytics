package com.garmin.analytics.chat.ai.routing;

import com.garmin.analytics.chat.model.AnalyticsSelection;

/** Validated routing decisions. Only Analyze carries executable parameters. */
public sealed interface RoutingDecision {

  record Analyze(AnalyticsSelection selection) implements RoutingDecision {}
  record Clarify(String question) implements RoutingDecision {}
  record Unsupported(UnsupportedReason reason) implements RoutingDecision {}

  enum UnsupportedReason {
    RELATIONSHIP, UNSUPPORTED_METRIC, UNSUPPORTED_CALCULATION, HEALTH_ADVICE, OTHER
  }
}
