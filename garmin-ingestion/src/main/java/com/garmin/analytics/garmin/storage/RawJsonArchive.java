package com.garmin.analytics.garmin.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import com.garmin.analytics.garmin.service.GarminPayload;

public final class RawJsonArchive {

    private final Path rawBasePath;

    public RawJsonArchive(Path rawBasePath) {
        this.rawBasePath = Objects.requireNonNull(rawBasePath, "rawBasePath is required");
    }

    public Path persist(GarminPayload payload) throws IOException {
        Path targetDirectory = rawBasePath
                .resolve(payload.dataset())
                .resolve(String.format("%04d", payload.date().getYear()))
                .resolve(String.format("%02d", payload.date().getMonthValue()));

        Files.createDirectories(targetDirectory);

        Path targetFile = targetDirectory.resolve(payload.date().toString() + ".json");
        Path tempFile = Files.createTempFile(targetDirectory, payload.date() + "-", ".tmp");

        try {
            Files.writeString(tempFile, payload.rawJson(), StandardCharsets.UTF_8);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return targetFile;
    }
}
