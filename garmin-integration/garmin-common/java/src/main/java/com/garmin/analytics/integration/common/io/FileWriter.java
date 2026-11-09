package com.garmin.analytics.integration.common.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes an unchanged UTF-8 response to the caller-selected destination file. */
public final class FileWriter {

  public Path write(Path destination, String rawJson) throws IOException {
    Path target = destination.toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    return Files.writeString(target, rawJson, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }
}
