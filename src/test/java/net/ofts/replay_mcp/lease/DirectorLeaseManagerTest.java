package net.ofts.replay_mcp.lease;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.*;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DirectorLeaseManagerTest {
    @Test void enforcesExclusionFencingExpiryAndCleanup() {
        MutableClock clock = new MutableClock(); var reasons = new ArrayList<DirectorLeaseManager.RevocationReason>();
        DirectorLeaseManager manager = new DirectorLeaseManager(clock, Duration.ofSeconds(15), Duration.ofMinutes(5), new SecureRandom(), reasons::add);
        Lease first = manager.acquire("a", "Alice");
        assertEquals(BridgeError.CONTROL_BUSY, assertThrows(BridgeException.class, () -> manager.acquire("b", "Bob")).error());
        assertEquals(BridgeError.STALE_FENCE, assertThrows(BridgeException.class, () -> manager.require("a", first.id(), first.epoch() + 1)).error());
        manager.release("a", first.id(), first.epoch());
        assertTrue(reasons.contains(DirectorLeaseManager.RevocationReason.RELEASED));
        Lease second = manager.acquire("b", "Bob");
        assertTrue(second.epoch() > first.epoch());
        clock.advance(Duration.ofSeconds(15));
        assertTrue(manager.status().isEmpty());
        assertTrue(reasons.contains(DirectorLeaseManager.RevocationReason.EXPIRED));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
