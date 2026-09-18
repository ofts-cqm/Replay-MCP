package net.ofts.replay_mcp.observation;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class StableObservationIds<T> {
    private final SecureRandom random;
    private final Map<String, T> targets = new HashMap<>();
    private long generation;

    public StableObservationIds(SecureRandom random) { this.random = random; }

    public synchronized void resetSession() { generation++; targets.clear(); }

    public synchronized String register(T target) {
        Optional<String> existing = targets.entrySet().stream().filter(e -> e.getValue().equals(target)).map(Map.Entry::getKey).findFirst();
        if (existing.isPresent()) return existing.get();
        String id;
        do { id = generation + "-" + Long.toUnsignedString(random.nextLong(), 36); } while (targets.containsKey(id));
        targets.put(id, target);
        return id;
    }

    public synchronized T resolve(String id) {
        T result = targets.get(id);
        if (result == null) throw new BridgeException(BridgeError.CONFLICT, "stale or unknown observation id");
        return result;
    }
}
