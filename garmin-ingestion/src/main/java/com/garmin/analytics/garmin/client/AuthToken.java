package com.garmin.analytics.garmin.client;

import java.time.Instant;

public record AuthToken(String accessToken, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return expiresAt.minusSeconds(30).isBefore(now);
    }
}
