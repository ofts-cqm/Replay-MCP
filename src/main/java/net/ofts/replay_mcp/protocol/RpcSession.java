package net.ofts.replay_mcp.protocol;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RpcSession {
    private final String connectionId;
    private final byte[] expectedToken;
    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final AtomicBoolean helloComplete = new AtomicBoolean();

    public RpcSession(String connectionId, byte[] expectedToken) {
        this.connectionId = Objects.requireNonNull(connectionId);
        this.expectedToken = expectedToken.clone();
    }

    public String connectionId() { return connectionId; }
    public boolean authenticated() { return authenticated.get(); }
    public boolean helloComplete() { return helloComplete.get(); }

    public boolean authenticate(byte[] suppliedToken) {
        boolean valid = MessageDigest.isEqual(expectedToken, suppliedToken);
        authenticated.compareAndSet(false, valid);
        return valid;
    }

    public void requireMethod(String method) {
        if (!authenticated()) {
            throw new BridgeException(BridgeError.UNAUTHENTICATED, "authentication required");
        }
        if (!helloComplete() && !"system.hello".equals(method)) {
            throw new BridgeException(BridgeError.PROTOCOL_MISMATCH, "system.hello must be the first RPC");
        }
        if (helloComplete() && "system.hello".equals(method)) {
            throw new BridgeException(BridgeError.CONFLICT, "system.hello already completed");
        }
    }

    public void completeHello() {
        if (!authenticated() || !helloComplete.compareAndSet(false, true)) {
            throw new BridgeException(BridgeError.PROTOCOL_MISMATCH, "invalid hello state");
        }
    }
}
