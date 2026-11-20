package dev.garmin.evaluation.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.garmin.evaluation.core.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class PrivateFiles {
    private final Path requestedWorkspace;
    private final Path workspace;
    private final Path root;

    public PrivateFiles(Path workspace) throws IOException {
        requestedWorkspace = workspace.toAbsolutePath().normalize();
        this.workspace = workspace.toRealPath();
        root = this.workspace.resolve("data/evaluations");
    }

    public Path destination(Path path) throws IOException {
        Path target = requestedWorkspace.resolve(path).normalize();
        if (target.startsWith(requestedWorkspace)) {
            target = workspace.resolve(requestedWorkspace.relativize(target));
        }
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Output must be inside data/evaluations");
        }
        Path current = workspace;
        for (Path part : workspace.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Evaluation output cannot traverse symbolic links");
            }
            if (current.equals(root) && Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                createDirectory(current, true);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Evaluation output parent must exist");
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Evaluation output already exists");
        }
        return target;
    }

    public Path createCapture(Path output) throws IOException {
        Path target = destination(output);
        createDirectory(target, false);
        return target;
    }

    private static void createDirectory(Path path, boolean allowExisting) throws IOException {
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            if (!allowExisting || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw exists;
            }
        }
    }

    public static BufferedWriter newWriter(Path path) throws IOException {
        var file = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return new BufferedWriter(new OutputStreamWriter(java.nio.channels.Channels.newOutputStream(file), UTF_8));
    }

    public static void save(Path path, JsonNode value) throws IOException {
        write(path, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    public static void write(Path path, String text) throws IOException {
        try (var writer = newWriter(path)) {
            writer.write(text);
        }
    }

    public static void saveRows(Path path, List<JsonNode> rows) throws IOException {
        try (var writer = newWriter(path)) {
            for (var row : rows) {
                writer.write(Json.encode(row));
                writer.newLine();
            }
        }
    }

    public static JsonNode read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return Json.MAPPER.readTree(input);
        }
    }

    public static List<JsonNode> readRows(Path path) throws IOException {
        var rows = new ArrayList<JsonNode>();
        try (var reader = Files.newBufferedReader(path)) {
            for (String line; (line = reader.readLine()) != null;) {
                if (!line.isBlank()) {
                    rows.add(Json.MAPPER.readTree(line));
                }
            }
        }
        return rows;
    }

    public static String fileHash(Path path) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
