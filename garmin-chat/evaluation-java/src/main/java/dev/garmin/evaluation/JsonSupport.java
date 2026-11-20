package dev.garmin.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class JsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private JsonSupport() {}
    public static ObjectMapper mapper() { return MAPPER; }
    public static JsonNode read(Path path) throws IOException { return MAPPER.readTree(Files.readString(path)); }
    public static String hash(JsonNode value) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
    public static void writeNew(Path path, JsonNode value) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }
}