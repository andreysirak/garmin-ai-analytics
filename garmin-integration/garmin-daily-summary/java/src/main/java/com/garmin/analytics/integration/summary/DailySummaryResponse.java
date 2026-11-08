package com.garmin.analytics.integration.summary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/** The subset of Garmin's daily summary used for response validation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailySummaryResponse(
    LocalDate calendarDate,
    Long totalSteps,
    Long totalDistanceMeters,
    Integer restingHeartRate,
    Integer averageStressLevel,
    Integer bodyBatteryChargedValue,
    Integer bodyBatteryDrainedValue,
    Integer totalKilocalories) {}
