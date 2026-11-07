package com.garmin.analytics.garmin.service;

import java.time.LocalDate;

public record GarminPayload(
        String dataset,
        LocalDate date,
        String rawJson,
        boolean empty
) {}
