package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Keeps the Java protocol parser aligned with the same language-neutral golden
 * fixtures validated by the TypeScript/Ajv suite. JSON Schema provides the
 * exhaustive shape check; this test proves the Java wire invariants agree.
 */
final class BridgeFixtureContractTest {
    private static final Set<String> ERRORS = Set.of(
            "protocol_mismatch", "unauthenticated", "invalid_request", "capability_unavailable",
            "invalid_mode", "control_required", "stale_fence", "control_busy", "conflict",
            "policy_denied", "timeout", "cancelled", "internal_error", "recording_not_armed");

    @Test
    void acceptsSharedValidFixtures() throws IOException {
        for (JsonElement element : fixtures("valid.json")) {
            JsonObject fixture = element.getAsJsonObject();
            assertDoesNotThrow(() -> validate(fixture.get("schema").getAsString(), fixture.get("value")));
        }
    }

    @Test
    void rejectsSharedInvalidFixtures() throws IOException {
        for (JsonElement element : fixtures("invalid.json")) {
            JsonObject fixture = element.getAsJsonObject();
            assertThrows(RuntimeException.class, () -> validate(fixture.get("schema").getAsString(), fixture.get("value")));
        }
    }

    private static JsonArray fixtures(String name) throws IOException {
        Path path = Path.of("protocol", "bridge-v1", "fixtures", name);
        return JsonParser.parseString(Files.readString(path)).getAsJsonArray();
    }

    private static void validate(String schema, JsonElement value) {
        JsonObject object = value.getAsJsonObject();
        switch (schema) {
            case "discovery.schema.json" -> validateDiscovery(object);
            case "rpc.schema.json" -> validateRpc(object);
            case "artifact.schema.json" -> validateArtifact(object);
            case "lease.schema.json" -> validateLease(object);
            case "status.schema.json" -> validateStatus(object);
            case "action.schema.json" -> validateAction(object);
            case "timeline.schema.json" -> validateTimeline(object);
            case "render.schema.json" -> validateRender(object);
            default -> throw new IllegalArgumentException("unknown fixture schema " + schema);
        }
    }

    private static void validateDiscovery(JsonObject value) {
        java.util.UUID.fromString(requiredString(value, "instanceId"));
        if (requiredLong(value, "processId") < 1) throw new IllegalArgumentException();
        long port = requiredLong(value, "port");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException();
        java.time.Instant.parse(requiredString(value, "createdAt"));
        if (!"replay-mcp.bridge/1".equals(requiredString(value, "protocolVersion"))) throw new IllegalArgumentException();
        for (String key : new String[]{"displayName", "gameDirectory", "modVersion", "minecraftVersion", "replayModVersion"}) requiredString(value, key);
    }

    private static void validateRpc(JsonObject value) {
        if (!"2.0".equals(requiredString(value, "jsonrpc"))) throw new IllegalArgumentException();
        boolean request = value.has("method") && value.has("id");
        boolean event = value.has("method") && !value.has("id");
        boolean success = value.has("result") && value.has("id");
        boolean failure = value.has("error") && value.has("id");
        int shapes = (request ? 1 : 0) + (event ? 1 : 0) + (success ? 1 : 0) + (failure ? 1 : 0);
        if (shapes != 1) throw new IllegalArgumentException();
        if (request || event) {
            if (!requiredString(value, "method").matches("[a-z]+(?:\\.[a-z_]+)+") || !value.has("params") || !value.get("params").isJsonObject()) throw new IllegalArgumentException();
        }
        if (failure) {
            JsonObject error = value.getAsJsonObject("error");
            if (!ERRORS.contains(requiredString(error, "code"))) throw new IllegalArgumentException();
            requiredString(error, "message");
            if (!error.has("data") || !error.get("data").isJsonObject()) throw new IllegalArgumentException();
        }
    }

    private static void validateArtifact(JsonObject value) {
        requiredString(value, "path");
        if (!requiredString(value, "mime_type").matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) throw new IllegalArgumentException();
        if (requiredLong(value, "size") < 0) throw new IllegalArgumentException();
        if (!requiredString(value, "sha256").matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
        if (!value.has("complete") || !value.get("complete").isJsonPrimitive()) throw new IllegalArgumentException();
    }

    private static void validateLease(JsonObject value) {
        if (requiredString(value, "lease_id").length() < 32 || requiredLong(value, "fence") < 1) throw new IllegalArgumentException();
        String owner = requiredString(value, "owner_label");
        if (owner.length() > 64) throw new IllegalArgumentException();
        java.time.Instant.parse(requiredString(value, "expires_at"));
    }

    private static void validateStatus(JsonObject value) {
        if (!Set.of("game_offline", "live_idle", "live_recording", "replay_loading", "replay_loaded", "rendering").contains(requiredString(value, "runtime_mode"))) throw new IllegalArgumentException();
        for (String key : new String[]{"capabilities", "lease", "lease_policy", "command_policy", "flight_policy"}) if (!value.has(key) || !value.get(key).isJsonObject()) throw new IllegalArgumentException(key);
        if (!value.has("allowed_artifact_roots") || !value.get("allowed_artifact_roots").isJsonArray() || value.getAsJsonArray("allowed_artifact_roots").isEmpty()) throw new IllegalArgumentException();
    }

    private static void validateAction(JsonObject value) {
        requiredString(value, "request_id");
        if (!value.has("actions") || !value.get("actions").isJsonArray() || value.getAsJsonArray("actions").isEmpty()) throw new IllegalArgumentException();
        for (JsonElement element : value.getAsJsonArray("actions")) {
            JsonObject action = element.getAsJsonObject();
            if (requiredString(action, "kind").equals("navigate_to")) {
                for (String coordinate : new String[]{"x", "y", "z"}) {
                    if (!action.has(coordinate) || !action.get(coordinate).isJsonPrimitive() || !action.get(coordinate).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(coordinate);
                }
                if (action.has("tolerance")) {
                    double tolerance = action.get("tolerance").getAsDouble();
                    if (tolerance < 0.25 || tolerance > 4.0) throw new IllegalArgumentException("tolerance");
                }
                if (action.has("sprint") && !action.get("sprint").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("sprint");
            }
        }
    }

    private static void validateTimeline(JsonObject value) {
        if (value.has("tracks")) {
            requiredString(value, "revision");
            if (!value.get("tracks").isJsonObject()) throw new IllegalArgumentException();
        } else {
            requiredString(value, "request_id"); requiredString(value, "base_revision");
            if (!value.has("operations") || !value.get("operations").isJsonArray() || value.getAsJsonArray("operations").isEmpty()) throw new IllegalArgumentException();
        }
    }

    private static void validateRender(JsonObject value) {
        requiredString(value, "job_id");
        if (!Set.of("queued", "running", "completed", "failed", "cancelled").contains(requiredString(value, "status"))) throw new IllegalArgumentException();
        if (value.has("progress") && (value.get("progress").getAsDouble() < 0 || value.get("progress").getAsDouble() > 1)) throw new IllegalArgumentException();
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) throw new IllegalArgumentException(key);
        String value = object.get(key).getAsString();
        if (value.isBlank()) throw new IllegalArgumentException(key);
        return value;
    }

    private static long requiredLong(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) throw new IllegalArgumentException(key);
        return object.get(key).getAsLong();
    }
}
