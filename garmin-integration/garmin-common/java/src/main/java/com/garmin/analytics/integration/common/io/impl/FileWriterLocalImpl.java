package com.garmin.analytics.integration.common.io.impl;

import com.garmin.analytics.integration.common.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes unchanged UTF-8 response text to the local filesystem using Java NIO.
 *
 * <p>Missing parent directories are created and existing files are never replaced. A failed write
 * may leave a partial file. Mounted Kubernetes volumes use the same filesystem operations.
 */
public final class FileWriterLocalImpl implements FileWriter {

  /** {@inheritDoc} */
  @Override
  public Path write(Path destination, String rawJson) throws IOException {
    Path target = destination.toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    return Files.writeString(target, rawJson, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }
}
