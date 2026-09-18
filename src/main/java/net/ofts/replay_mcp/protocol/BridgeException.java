package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonObject;

public final class BridgeException extends RuntimeException {
    private final BridgeError error;
    private final JsonObject data;

    public BridgeException(BridgeError error, String message) {
        this(error, message, new JsonObject());
    }

    public BridgeException(BridgeError error, String message, JsonObject data) {
        super(message);
        this.error = error;
        this.data = data.deepCopy();
    }

    public BridgeError error() { return error; }
    public JsonObject data() { return data.deepCopy(); }
}
