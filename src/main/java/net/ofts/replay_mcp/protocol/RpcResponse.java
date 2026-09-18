package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public final class RpcResponse {
    private RpcResponse() {}

    public static JsonObject success(String id, JsonElement result) {
        JsonObject response = base(id);
        response.add("result", result == null ? JsonNull.INSTANCE : result);
        return response;
    }

    public static JsonObject failure(String id, BridgeException failure) {
        JsonObject response = base(id);
        JsonObject error = new JsonObject();
        error.addProperty("code", failure.error().wireName());
        error.addProperty("message", failure.getMessage());
        error.add("data", failure.data());
        response.add("error", error);
        return response;
    }

    public static JsonObject internalFailure(String id) {
        return failure(id, new BridgeException(BridgeError.INTERNAL_ERROR, "internal bridge error"));
    }

    private static JsonObject base(String id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        return response;
    }
}
