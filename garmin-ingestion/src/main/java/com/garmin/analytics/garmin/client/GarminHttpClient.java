package com.garmin.analytics.garmin.client;

import java.io.IOException;
import java.util.Map;

public interface GarminHttpClient {
    String get(String path, Map<String, String> queryParams) throws IOException, InterruptedException;
}
