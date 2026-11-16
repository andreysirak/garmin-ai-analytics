package com.garmin.analytics.chat;

import java.time.LocalDate;

public record ChatAnswer(String answer, LocalDate startDate, LocalDate endDate) {}
