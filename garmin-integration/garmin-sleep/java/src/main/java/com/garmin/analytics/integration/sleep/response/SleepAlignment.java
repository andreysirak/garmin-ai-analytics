package com.garmin.analytics.integration.sleep.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
/** Garmin sleep alignment details. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepAlignment(
    Long userId,
    LocalDate calendarDate,
    Long deviceId,
    Boolean isPreferredActivityTracker,
    Integer sleepNeedBaselineMins,
    Integer optimalSleepWindowStartMins,
    Integer optimalSleepWindowEndMins,
    String status,
    Integer optimalSleepWindowMidpointMins,
    Integer lastSleepMidpointMins) {}
