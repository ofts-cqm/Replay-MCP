package net.ofts.replay_mcp.control;

/**
 * Temporarily forces pause-on-focus-loss off while a director lease is active,
 * then restores the user's previous setting when control ends.
 */
public final class PauseOnLostFocusOverride {
    private Boolean restoreValue;

    public boolean update(boolean controlActive, boolean currentValue) {
        if (controlActive) {
            if (restoreValue == null) restoreValue = currentValue;
            return false;
        }
        if (restoreValue == null) return currentValue;
        boolean restored = restoreValue;
        restoreValue = null;
        return restored;
    }
}
