package com.garmin.analytics.integration.common.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileWriterTest {

  @TempDir Path temporaryDirectory;

  @Test
  void createsParentDirectoriesAndWritesUtf8Json() throws IOException {
    Path destination = temporaryDirectory.resolve("nested/data.json");
    String rawJson = "{\"label\":\"garmin café\"}";

    new JsonFileWriter().write(destination, rawJson);

    assertEquals(rawJson, Files.readString(destination));
  }

  @Test
  void replacesExistingFileWithoutChangingRawJson() throws IOException {
    Path destination = temporaryDirectory.resolve("data.json");
    Files.writeString(destination, "{\"old\":true}");

    new JsonFileWriter().write(destination, "{\"new\":true}");

    assertEquals("{\"new\":true}", Files.readString(destination));
    try (var files = Files.list(temporaryDirectory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void removesTemporaryFileWhenReplacementFails() throws IOException {
    Path destinationDirectory = Files.createDirectory(temporaryDirectory.resolve("target"));

    assertThrows(
        IOException.class,
        () -> new JsonFileWriter().write(destinationDirectory, "{\"data\":true}"));

    try (var files = Files.list(temporaryDirectory)) {
      assertTrue(files.allMatch(path -> path.equals(destinationDirectory)));
    }
  }
}
