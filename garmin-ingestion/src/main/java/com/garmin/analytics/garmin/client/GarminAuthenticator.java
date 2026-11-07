package com.garmin.analytics.garmin.client;

import java.io.IOException;

public interface GarminAuthenticator {
    AuthToken authenticate() throws IOException, InterruptedException;
}
