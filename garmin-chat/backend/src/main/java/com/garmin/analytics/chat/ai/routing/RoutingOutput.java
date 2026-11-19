package com.garmin.analytics.chat.ai.routing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.garmin.analytics.chat.ai.routing.RoutingDecision.UnsupportedReason;
import com.garmin.analytics.chat.model.AnalyticsSelection;

@JsonPropertyOrder({"route", "selection", "target", "question", "reason"})
public record RoutingOutput(
    @JsonProperty(required = true) Route route,
    AnalyticsSelection selection,
    ClarificationTarget target,
    String question,
    UnsupportedReason reason) {

  public enum Route { ANALYZE, CLARIFY, UNSUPPORTED }

  public enum ClarificationTarget { METRIC, CALCULATION, PERIOD, GOAL }
}
