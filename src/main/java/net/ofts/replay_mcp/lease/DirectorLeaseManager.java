package net.ofts.replay_mcp.lease;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class DirectorLeaseManager {
    public enum RevocationReason { RELEASED, EXPIRED, IDLE, DISCONNECTED, HUMAN_OVERRIDE, EMERGENCY_STOP }

    private final Clock clock;
    private final Duration ttl;
    private final Duration idleCeiling;
    private final SecureRandom random;
    private final Consumer<RevocationReason> terminalCleanup;
    private Lease active;
    private long epoch;

    public DirectorLeaseManager(Clock clock, Duration ttl, Duration idleCeiling,
                                SecureRandom random, Consumer<RevocationReason> terminalCleanup) {
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        this.idleCeiling = Objects.requireNonNull(idleCeiling);
        this.random = Objects.requireNonNull(random);
        this.terminalCleanup = Objects.requireNonNull(terminalCleanup);
    }

    public synchronized Lease acquire(String connectionId, String ownerLabel) {
        expireIfNeeded();
        if (active != null) {
            throw new BridgeException(BridgeError.CONTROL_BUSY, "control is held by " + active.ownerLabel());
        }
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        active = new Lease(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), connectionId,
                sanitizeOwner(ownerLabel), ++epoch, now, now, now.plus(ttl));
        return active;
    }

    public synchronized Lease heartbeat(String connectionId, String leaseId, long fence, boolean activeWork) {
        Lease lease = require(connectionId, leaseId, fence);
        Instant now = clock.instant();
        Instant lastActive = activeWork ? now : lease.lastActiveAt();
        if (!now.isBefore(lastActive.plus(idleCeiling))) {
            revoke(RevocationReason.IDLE);
            throw new BridgeException(BridgeError.CONTROL_REQUIRED, "lease idle ceiling reached");
        }
        active = new Lease(lease.id(), lease.connectionId(), lease.ownerLabel(), lease.epoch(),
                lease.acquiredAt(), lastActive, now.plus(ttl));
        return active;
    }

    public synchronized Lease require(String connectionId, String leaseId, long fence) {
        expireIfNeeded();
        if (active == null || !active.connectionId().equals(connectionId) || !active.id().equals(leaseId)) {
            throw new BridgeException(BridgeError.CONTROL_REQUIRED, "current director lease is required");
        }
        if (active.epoch() != fence) {
            throw new BridgeException(BridgeError.STALE_FENCE, "fencing epoch is stale");
        }
        return active;
    }

    public synchronized void release(String connectionId, String leaseId, long fence) {
        require(connectionId, leaseId, fence);
        revoke(RevocationReason.RELEASED);
    }

    public synchronized void disconnected(String connectionId) {
        if (active != null && active.connectionId().equals(connectionId)) revoke(RevocationReason.DISCONNECTED);
    }

    public synchronized void humanOverride() { if (active != null) revoke(RevocationReason.HUMAN_OVERRIDE); }
    public synchronized void emergencyStop() { if (active != null) revoke(RevocationReason.EMERGENCY_STOP); else terminalCleanup.accept(RevocationReason.EMERGENCY_STOP); }
    public synchronized Optional<Lease> status() { expireIfNeeded(); return Optional.ofNullable(active); }
    public synchronized long epoch() { return epoch; }

    private void expireIfNeeded() {
        if (active != null && !clock.instant().isBefore(active.expiresAt())) revoke(RevocationReason.EXPIRED);
    }

    private void revoke(RevocationReason reason) {
        active = null;
        epoch++;
        terminalCleanup.accept(reason);
    }

    private static String sanitizeOwner(String owner) {
        if (owner == null || owner.isBlank()) return "anonymous director";
        String clean = owner.strip().replaceAll("[\\p{Cntrl}]", "");
        return clean.substring(0, Math.min(clean.length(), 64));
    }
}
