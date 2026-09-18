package net.ofts.replay_mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReplayMcpConfigTest {
    @TempDir Path temp;

    @Test void writesSecureDefaultsAndRoundTrips() throws Exception {
        ReplayMcpConfig config = ReplayMcpConfig.loadOrCreate(temp);
        assertTrue(config.bridgeEnabled);
        assertFalse(config.commandsEnabled);
        assertTrue(config.flightAutomationEnabled);
        assertTrue(config.physicalInputRevokesLease);
        assertEquals(15, config.leaseTtlSeconds);
        assertEquals(5, config.expectedHeartbeatSeconds);
        assertEquals(300, config.idleCeilingSeconds);
        assertTrue(config.commandDenyPatterns.isEmpty());
        assertEquals(15, ReplayMcpConfig.loadOrCreate(temp).leaseTtlSeconds);
    }
}
