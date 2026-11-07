package com.garmin.analytics.garmin.client;

import java.io.IOException;

public interface TokenProvider {
    String getAccessToken() throws IOException, InterruptedException;

    void invalidate();
}
