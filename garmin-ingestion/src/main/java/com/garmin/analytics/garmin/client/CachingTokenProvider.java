package com.garmin.analytics.garmin.client;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class CachingTokenProvider implements TokenProvider {

    private final GarminAuthenticator authenticator;
    private final Clock clock;
    private volatile AuthToken cachedToken;

    public CachingTokenProvider(GarminAuthenticator authenticator) {
        this(authenticator, Clock.systemUTC());
    }

    public CachingTokenProvider(GarminAuthenticator authenticator, Clock clock) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public synchronized String getAccessToken() throws IOException, InterruptedException {
        Instant now = Instant.now(clock);
        if (cachedToken == null || cachedToken.isExpired(now)) {
            cachedToken = authenticator.authenticate();
        }
        return cachedToken.accessToken();
    }

    @Override
    public synchronized void invalidate() {
        cachedToken = null;
    }
}
