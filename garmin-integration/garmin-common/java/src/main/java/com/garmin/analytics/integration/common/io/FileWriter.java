package com.garmin.analytics.integration.common.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Archives unchanged response text in newly created UTF-8 files.
 *
 * <p>Missing parent directories are created and existing files are never replaced. A failed write
 * may leave a partial file.
 */
public final class FileWriter {

  /**
   * Writes the response text to the caller-selected destination file.
   *
   * @param destination the non-null destination file path
   * @param rawJson the non-null response text to write without modification
   * @return the absolute, normalized path of the written file
   * @throws java.nio.file.FileAlreadyExistsException if the destination already exists
   * @throws IOException if parent directories cannot be created or the file cannot be written
   */
  public Path write(Path destination, String rawJson) throws IOException {
    Path target = destination.toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    return Files.writeString(target, rawJson, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }
}
