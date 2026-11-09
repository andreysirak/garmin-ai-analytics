package com.garmin.analytics.integration.sleep.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Value of any dynamically named sleepScores entry. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Score(
    Double value,
    String qualifierKey,
    Double optimalStart,
    Double optimalEnd,
    Double idealStartInSeconds,
    Double idealEndInSeconds) {}
