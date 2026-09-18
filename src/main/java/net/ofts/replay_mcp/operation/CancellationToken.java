package net.ofts.replay_mcp.operation;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public boolean cancel() { return cancelled.compareAndSet(false, true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void throwIfCancelled() {
        if (isCancelled()) throw new BridgeException(BridgeError.CANCELLED, "operation cancelled");
    }
}
