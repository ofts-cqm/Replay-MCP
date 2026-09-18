package net.ofts.replay_mcp.protocol;

/** Stable machine-readable bridge failures. */
public enum BridgeError {
    PROTOCOL_MISMATCH("protocol_mismatch"),
    UNAUTHENTICATED("unauthenticated"),
    INVALID_REQUEST("invalid_request"),
    CAPABILITY_UNAVAILABLE("capability_unavailable"),
    INVALID_MODE("invalid_mode"),
    CONTROL_REQUIRED("control_required"),
    STALE_FENCE("stale_fence"),
    CONTROL_BUSY("control_busy"),
    CONFLICT("conflict"),
    POLICY_DENIED("policy_denied"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled"),
    INTERNAL_ERROR("internal_error"),
    RECORDING_NOT_ARMED("recording_not_armed");

    private final String wireName;

    BridgeError(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
