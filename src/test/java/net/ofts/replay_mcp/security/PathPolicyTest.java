package net.ofts.replay_mcp.security;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathPolicyTest {
    @TempDir Path temp;
    @Test void confinesWrites() throws Exception {
        PathPolicy policy = new PathPolicy(temp.resolve("root"));
        assertTrue(policy.resolveForWrite("shots/a.mp4").startsWith(temp.resolve("root").toRealPath()));
        assertEquals(BridgeError.POLICY_DENIED, assertThrows(BridgeException.class, () -> policy.resolveForWrite("../escape")).error());
    }
}
