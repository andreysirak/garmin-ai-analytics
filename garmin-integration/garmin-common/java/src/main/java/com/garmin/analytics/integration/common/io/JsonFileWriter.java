package com.garmin.analytics.integration.common.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes complete UTF-8 JSON files with atomic replacement. */
public final class JsonFileWriter {

  public void write(Path destination, String rawJson) throws IOException {
    Objects.requireNonNull(destination, "destination is required");
    Objects.requireNonNull(rawJson, "rawJson is required");
    Path absoluteDestination = destination.toAbsolutePath().normalize();
    Path directory = absoluteDestination.getParent();
    if (directory == null) {
      throw new IOException("JSON destination must have a parent directory");
    }

    Files.createDirectories(directory);
    String prefix = absoluteDestination.getFileName().toString();
    if (prefix.length() < 3) {
      prefix = (prefix + "___").substring(0, 3);
    }
    Path temporaryFile = Files.createTempFile(directory, prefix, ".tmp");
    boolean moved = false;
    try {
      Files.writeString(temporaryFile, rawJson, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporaryFile,
            absoluteDestination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("Atomic replacement is not supported for " + destination, exception);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporaryFile);
      }
    }
  }
}
