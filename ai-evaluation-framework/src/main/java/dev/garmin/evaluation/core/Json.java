package dev.garmin.evaluation.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Expected nonblank text for " + key);
        }
        return value.asText();
    }

    public static String encode(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode evaluation JSON", e);
        }
    }

    public static String hash(JsonNode value) {
        try {
            return sha256(MAPPER.writeValueAsBytes(sorted(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot hash evaluation JSON", e);
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            return object().setAll(fields);
        }
        if (value.isArray()) {
            var array = MAPPER.createArrayNode();
            value.forEach(item -> array.add(sorted(item)));
            return array;
        }
        // Jackson normalizes decimal scale on read. Normalize on write as well.
        if (value.isFloatingPointNumber()) {
            return MAPPER.getNodeFactory().numberNode(value.decimalValue().stripTrailingZeros());
        }
        return value;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
