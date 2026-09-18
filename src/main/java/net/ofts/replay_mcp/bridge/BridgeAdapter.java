package net.ofts.replay_mcp.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.operation.CancellationToken;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

/** Version-sensitive Minecraft and Replay Mod boundary. */
public interface BridgeAdapter {
    default void eventSink(EventSink sink) { }
    JsonObject status();
    JsonObject capabilities();
    default boolean flightGranted() { return false; }
    default void releaseAllInputs() { }
    default void timelineChanged(JsonObject timeline) { }
    default JsonElement invoke(String method, JsonObject params, CancellationToken cancellation) {
        throw new BridgeException(BridgeError.CAPABILITY_UNAVAILABLE, method + " is unavailable in the installed runtime");
    }
}
