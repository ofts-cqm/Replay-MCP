package net.ofts.replay_mcp.bridge;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface EventSink {
    void publish(String method, JsonObject params);
    EventSink NONE = (method, params) -> { };
}
