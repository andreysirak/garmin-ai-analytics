package com.garmin.analytics.chat.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("garmin.chat")
public record GarminChatProperties(
    @NotNull @Positive Long profileId,
    @NotNull ZoneId timezone,
    @Positive int contextTokens,
    @Positive int responseTokens,
    @Positive int maxQueryDays) {}
