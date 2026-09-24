package net.ofts.replay_mcp.protocol;

/** Stable machine-readable bridge failures. */
public enum BridgeError {
    PROTOCOL_MISMATCH("protocol_mismatch"),
    UNAUTHENTICATED("unauthenticated"),
    INVALID_REQUEST("invalid_request"),
    CAPABILITY_UNAVAILABLE("capability_unavailable"),
    INVALID_MODE("invalid_mode"),
    QUERY_TOO_LARGE("query_too_large"),
    OUTSIDE_LOADED_AREA("outside_loaded_area"),
    NO_LOADED_COVERAGE("no_loaded_coverage"),
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
