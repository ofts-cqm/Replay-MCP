package net.ofts.replay_mcp.protocol;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class IdempotencyStore {
    private record Entry(String fingerprint, JsonElement result) { }
    private final int capacity;
    private final Map<String, Entry> entries;

    public IdempotencyStore(int capacity) {
        this.capacity = capacity;
        entries = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, IdempotencyStore.Entry> eldest) { return size() > IdempotencyStore.this.capacity; }
        };
    }

    public synchronized Optional<JsonElement> lookup(String requestId, JsonElement params) {
        Entry entry = entries.get(requestId);
        if (entry == null) return Optional.empty();
        if (!entry.fingerprint().equals(CanonicalJson.sha256(params))) {
            throw new BridgeException(BridgeError.CONFLICT, "request_id was reused with different parameters");
        }
        return Optional.of(entry.result().deepCopy());
    }

    public synchronized void remember(String requestId, JsonElement params, JsonElement result) {
        entries.put(requestId, new Entry(CanonicalJson.sha256(params), result.deepCopy()));
    }
}
