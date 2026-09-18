package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;

public record RpcRequest(String id, String method, JsonObject params, Duration deadline) {
    public static RpcRequest parse(JsonObject object, ProtocolLimits limits) {
        if (!"2.0".equals(string(object, "jsonrpc"))) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, "jsonrpc must be 2.0");
        }
        JsonElement idValue = object.get("id");
        if (idValue == null || idValue.isJsonNull() || !(idValue.isJsonPrimitive())) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, "request id is required");
        }
        String id = idValue.getAsString();
        String method = string(object, "method");
        if (method == null || method.isBlank() || method.length() > 128) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, "valid method is required");
        }
        JsonObject params = object.has("params") && object.get("params").isJsonObject()
                ? object.getAsJsonObject("params") : new JsonObject();
        long deadlineMs = params.has("deadline_ms") ? params.get("deadline_ms").getAsLong() : 30_000;
        return new RpcRequest(id, method, params.deepCopy(), limits.deadline(deadlineMs));
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
