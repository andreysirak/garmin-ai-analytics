package com.garmin.analytics.integration.common.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriterTest {

  private final FileWriter writer = new FileWriter();
  @TempDir Path temporaryDirectory;

  @Test
  void writesUnchangedUtf8ToTheExactDestination() throws IOException {
    Path destination = temporaryDirectory.resolve("caller-selected/nested/2026-11-07.json");
    String rawJson = " \r\n{ \"unknown\": \"garmin café\", \"number\": 1.00, "
        + "\"value\": null }\n";

    Path archived = writer.write(destination, rawJson);

    assertEquals(destination, archived);
    assertArrayEquals(rawJson.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(destination));
    try (var files = Files.list(destination.getParent())) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void doesNotOverwriteAnExistingDestination() throws IOException {
    Path destination = temporaryDirectory.resolve("2026-11-07.json");
    String previous = " {\"previous\":true}\n";
    Files.writeString(destination, previous);

    assertThrows(FileAlreadyExistsException.class, () -> writer.write(destination, "{}"));

    assertEquals(previous, Files.readString(destination));
  }

  @Test
  void keepsFilesInSeparateCallerSelectedDirectories() throws IOException {
    Path first = temporaryDirectory.resolve("run-a/2026-11-07.json");
    Path second = temporaryDirectory.resolve("run-b/2026-11-07.json");

    assertEquals(first, writer.write(first, "{\"first\":true}"));
    assertEquals(second, writer.write(second, "{\"second\":true}"));

    assertEquals("{\"first\":true}", Files.readString(first));
    assertEquals("{\"second\":true}", Files.readString(second));
  }

  @Test
  void preservesExistingFileWhenDestinationDirectoryCannotBeCreated() throws IOException {
    Path blocked = temporaryDirectory.resolve("blocked");
    Files.writeString(blocked, "preserved");

    assertThrows(IOException.class, () -> writer.write(blocked.resolve("date.json"), "{}"));

    assertEquals("preserved", Files.readString(blocked));
  }
}
