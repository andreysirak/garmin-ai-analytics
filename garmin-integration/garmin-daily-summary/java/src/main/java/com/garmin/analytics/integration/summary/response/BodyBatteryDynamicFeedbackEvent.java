package com.garmin.analytics.integration.summary.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BodyBatteryDynamicFeedbackEvent(
    Instant eventTimestampGmt,
    String bodyBatteryLevel,
    String feedbackShortType,
    String feedbackLongType) {}
