package com.garmin.analytics.integration.common.cli;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * Carries request settings and the archive destination for one ingestion run.
 *
 * <p>The archive date is independent of the query parameters sent to Garmin.
 *
 * @param date the archive date used in the destination filename
 * @param outputDirectory the final output directory selected by the caller
 * @param endpoint the validated absolute API path, without a query string or fragment
 * @param queryParameters the unencoded request parameter names and values; may be empty
 */
public record GarminApiIntegrationOptions(
    LocalDate date, Path outputDirectory, String endpoint, Map<String, String> queryParameters) {}
