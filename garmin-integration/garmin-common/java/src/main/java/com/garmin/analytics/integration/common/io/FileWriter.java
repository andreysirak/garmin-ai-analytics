package com.garmin.analytics.integration.common.io;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes unchanged response text to newly created files.
 *
 * <p>Implementations create missing parent directories and preserve existing destination files.
 */
public interface FileWriter {

  /**
   * Writes the complete response text as UTF-8 to the caller-selected destination file.
   *
   * <p>The file is closed before this method returns. A failed write may leave a partial file.
   *
   * @param destination the non-null destination file path
   * @param rawJson the non-null response text to write without modification
   * @return the absolute, normalized path of the written file
   * @throws java.nio.file.FileAlreadyExistsException if the destination already exists
   * @throws IOException if parent directories cannot be created or the file cannot be written
   */
  Path write(Path destination, String rawJson) throws IOException;
}
