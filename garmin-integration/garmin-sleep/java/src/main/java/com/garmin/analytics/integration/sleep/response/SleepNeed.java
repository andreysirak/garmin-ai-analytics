package com.garmin.analytics.integration.sleep.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
/** Sleep need details used for current and next sleep day. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepNeed(
    Long userProfilePk,
    LocalDate calendarDate,
    Long deviceId,
    Instant timestampGmt,
    Integer baseline,
    Integer actual,
    String feedback,
    String trainingFeedback,
    String sleepHistoryAdjustment,
    String hrvAdjustment,
    String napAdjustment,
    Boolean displayedForTheDay,
    Integer recommendedBedtimeStartMins,
    Integer recommendedBedtimeEndMins,
    Boolean preferredActivityTracker) {}
