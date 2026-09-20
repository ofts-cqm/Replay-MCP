package net.ofts.replay_mcp.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseOnLostFocusOverrideTest {
    @Test void disablesPauseDuringControlAndRestoresEnabledPreference() {
        PauseOnLostFocusOverride override = new PauseOnLostFocusOverride();

        assertFalse(override.update(true, true));
        assertFalse(override.update(true, false));
        assertTrue(override.update(false, false));
    }

    @Test void preservesAnAlreadyDisabledPreferenceAfterControl() {
        PauseOnLostFocusOverride override = new PauseOnLostFocusOverride();

        assertFalse(override.update(true, false));
        assertFalse(override.update(false, false));
    }

    @Test void keepsPauseDisabledIfItIsReenabledDuringControl() {
        PauseOnLostFocusOverride override = new PauseOnLostFocusOverride();

        assertFalse(override.update(true, true));
        assertFalse(override.update(true, true));
        assertTrue(override.update(false, false));
    }

    @Test void leavesSettingAloneWithoutControl() {
        PauseOnLostFocusOverride override = new PauseOnLostFocusOverride();

        assertTrue(override.update(false, true));
        assertFalse(override.update(false, false));
    }
}
