package net.ofts.replay_mcp.lease;

import java.time.Instant;

public record Lease(String id, String connectionId, String ownerLabel, long epoch,
                    Instant acquiredAt, Instant lastActiveAt, Instant expiresAt) { }
