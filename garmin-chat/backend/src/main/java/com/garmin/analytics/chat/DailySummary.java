package com.garmin.analytics.chat;

import java.time.LocalDate;

/** The complete JSONB object as serialized by PostgreSQL, without metric filtering. */
public record DailySummary(long profileId, LocalDate date, String payload) {}
