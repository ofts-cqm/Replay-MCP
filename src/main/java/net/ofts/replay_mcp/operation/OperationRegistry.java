package net.ofts.replay_mcp.operation;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class OperationRegistry {
    public record Operation(String id, String connectionId, String kind, Instant deadline, CancellationToken token) { }
    private final Map<String, Operation> active = new ConcurrentHashMap<>();
    private final Clock clock;

    public OperationRegistry(Clock clock) { this.clock = clock; }

    public Operation begin(String id, String connectionId, String kind, Instant deadline) {
        Operation operation = new Operation(id, connectionId, kind, deadline, new CancellationToken());
        if (active.putIfAbsent(id, operation) != null) {
            throw new BridgeException(BridgeError.CONFLICT, "operation id already active");
        }
        return operation;
    }

    public Optional<Operation> get(String id) { return Optional.ofNullable(active.get(id)); }
    public void complete(String id) { active.remove(id); }

    public boolean cancel(String id) {
        Operation operation = active.get(id);
        return operation != null && operation.token().cancel();
    }

    public void cancelForConnection(String connectionId) {
        active.values().stream().filter(o -> o.connectionId().equals(connectionId)).forEach(o -> o.token().cancel());
    }

    public void cancelAll() { active.values().forEach(o -> o.token().cancel()); }

    public void check(Operation operation) {
        operation.token().throwIfCancelled();
        if (!clock.instant().isBefore(operation.deadline())) {
            operation.token().cancel();
            throw new BridgeException(BridgeError.TIMEOUT, "operation deadline exceeded");
        }
    }
}
