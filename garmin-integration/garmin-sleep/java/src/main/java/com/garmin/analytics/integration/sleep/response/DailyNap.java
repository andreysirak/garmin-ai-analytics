package com.garmin.analytics.integration.sleep.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
/** Nap details from dailyNapDTOS. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyNap(
    Long userProfilePK,
    Long deviceId,
    LocalDate calendarDate,
    Long napTimeSec,
    Instant napStartTimestampGMT,
    Instant napEndTimestampGMT,
    String napFeedback,
    Integer napSource,
    Integer napStartTimeOffset,
    Integer napEndTimeOffset,
    Instant createTimestampGMT,
    Instant updateTimestampGMT) {}
