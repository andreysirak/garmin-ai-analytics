package com.garmin.analytics.garmin.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.garmin.analytics.garmin.service.GarminPayload;

class RawJsonArchiveTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesDeterministicPathWithAtomicReplace() throws Exception {
        RawJsonArchive archive = new RawJsonArchive(tempDirectory);
        LocalDate date = LocalDate.of(2026, 11, 5);

        Path firstWritePath = archive.persist(new GarminPayload("steps", date, "{\"totalSteps\":100}", false));
        Path secondWritePath = archive.persist(new GarminPayload("steps", date, "{\"totalSteps\":200}", false));

        assertEquals(firstWritePath, secondWritePath);
        assertEquals("{\"totalSteps\":200}", Files.readString(secondWritePath));

        try (Stream<Path> files = Files.walk(tempDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
