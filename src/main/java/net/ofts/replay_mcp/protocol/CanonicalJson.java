package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;

public final class CanonicalJson {
    private CanonicalJson() { }

    public static String encode(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonPrimitive()) return primitive(value.getAsJsonPrimitive());
        if (value.isJsonArray()) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : value.getAsJsonArray()) {
                if (!first) result.append(',');
                first = false;
                result.append(encode(child));
            }
            return result.append(']').toString();
        }
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey)).toList()) {
            if (!first) result.append(',');
            first = false;
            result.append(new JsonPrimitive(entry.getKey())).append(':').append(encode(entry.getValue()));
        }
        return result.append('}').toString();
    }

    public static String sha256(JsonElement value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String primitive(JsonPrimitive value) {
        if (value.isString()) return value.toString();
        if (value.isBoolean()) return value.getAsBoolean() ? "true" : "false";
        if (value.isNumber()) return value.getAsBigDecimal().stripTrailingZeros().toPlainString();
        return value.toString();
    }
}
