package net.ofts.replay_mcp.protocol;

import java.time.Duration;

public record ProtocolLimits(
        int maxTextBytes,
        int maxActions,
        int maxObservationItems,
        int maxTimelineOperations,
        Duration minDeadline,
        Duration maxDeadline) {

    public static ProtocolLimits defaults() {
        return new ProtocolLimits(1_048_576, 128, 4_096, 512,
                Duration.ofMillis(50), Duration.ofMinutes(10));
    }

    public ProtocolLimits {
        if (maxTextBytes < 1 || maxActions < 1 || maxObservationItems < 1
                || maxTimelineOperations < 1 || minDeadline.isNegative()
                || maxDeadline.compareTo(minDeadline) < 0) {
            throw new IllegalArgumentException("invalid protocol limits");
        }
    }

    public Duration deadline(long requestedMillis) {
        Duration requested = Duration.ofMillis(requestedMillis);
        if (requested.compareTo(minDeadline) < 0 || requested.compareTo(maxDeadline) > 0) {
            throw new BridgeException(BridgeError.INVALID_REQUEST,
                    "deadline_ms must be between " + minDeadline.toMillis() + " and " + maxDeadline.toMillis());
        }
        return requested;
    }
}
