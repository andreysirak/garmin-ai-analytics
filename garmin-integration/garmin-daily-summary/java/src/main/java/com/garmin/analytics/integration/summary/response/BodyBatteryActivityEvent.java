package com.garmin.analytics.integration.summary.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BodyBatteryActivityEvent(
    String eventType,
    Instant eventStartTimeGmt,
    Integer timezoneOffset,
    Long durationInMilliseconds,
    Integer bodyBatteryImpact,
    String feedbackType,
    String shortFeedback) {}
