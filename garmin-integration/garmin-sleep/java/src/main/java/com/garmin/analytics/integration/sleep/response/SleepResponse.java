package com.garmin.analytics.integration.sleep.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/** Garmin Personal API flat daily sleep response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleepResponse(
    Long id,
    Long userProfilePK,
    LocalDate calendarDate,
    Long sleepTimeSeconds,
    Long napTimeSeconds,
    Boolean sleepWindowConfirmed,
    String sleepWindowConfirmationType,
    Instant sleepStartTimestampGMT,
    Instant sleepEndTimestampGMT,
    Instant sleepStartTimestampLocal,
    Instant sleepEndTimestampLocal,
    Instant autoSleepStartTimestampGMT,
    Instant autoSleepEndTimestampGMT,
    String sleepQualityTypePK,
    String sleepResultTypePK,
    Long unmeasurableSleepSeconds,
    Long deepSleepSeconds,
    Long lightSleepSeconds,
    Long remSleepSeconds,
    Long awakeSleepSeconds,
    Boolean deviceRemCapable,
    Boolean sleepFromDevice,
    Double averageSpO2Value,
    Double lowestSpO2Value,
    Double highestSpO2Value,
    Integer numberOfEventsBelowThreshold,
    Double averageSpO2HRSleep,
    Double spo2Threshold,
    Double averageRespirationValue,
    Double lowestRespirationValue,
    Double highestRespirationValue,
    Integer awakeCount,
    Double avgSleepStress,
    String ageGroup,
    Integer avgHeartRate,
    String sleepScoreFeedback,
    String sleepScoreInsight,
    String sleepScorePersonalizedInsight,
    Map<String, Score> sleepScores,
    String userNote,
    String kidSleepNote,
    Integer sleepVersion,
    SleepNeed sleepNeed,
    SleepNeed nextSleepNeed,
    List<DailyNap> dailyNapDTOS,
    String breathingDisruptionSeverity,
    SleepAlignment sleepAlignment) {}
