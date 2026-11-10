package com.garmin.analytics.integration.common.cli;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/** Runtime arguments supplied by the ingestion operator. */
public record GarminApiIntegrationOptions(
    LocalDate date, Path outputDirectory, String endpoint, Map<String, String> queryParameters) {}
