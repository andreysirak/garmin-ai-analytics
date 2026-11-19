package com.garmin.analytics.chat.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record DateRange(
    @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate startDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate endDate) {}
