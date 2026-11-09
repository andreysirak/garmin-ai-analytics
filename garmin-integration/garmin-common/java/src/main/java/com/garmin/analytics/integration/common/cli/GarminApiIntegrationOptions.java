package com.garmin.analytics.integration.common.cli;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;

/** Runtime arguments supplied by the ingestion operator. */
public record GarminApiIntegrationOptions(LocalDate date, URI baseUri, Path outputDirectory) {}
