package net.ofts.replay_mcp.client;

import com.replaymod.replay.ReplaySender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySeekControllerTest {
    @Test void usesJumpToTimeForAsyncPlayback() {
        FakeReplaySender sender = new FakeReplaySender(true);

        ReplaySeekController.seek(sender, 12_345);

        assertEquals(List.of(12_345), sender.jumps);
        assertTrue(sender.sent.isEmpty());
    }

    @Test void usesPacketSendingForSyncPlayback() {
        FakeReplaySender sender = new FakeReplaySender(false);

        ReplaySeekController.seek(sender, 12_345);

        assertTrue(sender.jumps.isEmpty());
        assertEquals(List.of(12_345), sender.sent);
    }

    @Test void settledSeekSwitchesToSyncAndAppliesPreRoll() {
        FakeReplaySender sender = new FakeReplaySender(true);

        ReplaySeekController.seekSettled(sender, 12_345, 1_000);

        assertFalse(sender.async);
        assertTrue(sender.jumps.isEmpty());
        assertEquals(List.of(11_345, 12_345), sender.sent);
    }

    @Test void settledSeekClampsPreRollAtReplayStart() {
        FakeReplaySender sender = new FakeReplaySender(true);

        ReplaySeekController.seekSettled(sender, 250, 1_000);

        assertEquals(List.of(0, 250), sender.sent);
    }

    private static final class FakeReplaySender implements ReplaySender {
        private final List<Integer> jumps = new ArrayList<>();
        private final List<Integer> sent = new ArrayList<>();
        private boolean async;
        private double speed;

        private FakeReplaySender(boolean async) { this.async = async; }

        @Override public int currentTimeStamp() { return 0; }
        @Override public void setReplaySpeed(double speed) { this.speed = speed; }
        @Override public double getReplaySpeed() { return speed; }
        @Override public boolean isAsyncMode() { return async; }
        @Override public void setAsyncMode(boolean async) { this.async = async; }
        @Override public void setSyncModeAndWait() { async = false; }
        @Override public void jumpToTime(int timeMs) { jumps.add(timeMs); }
        @Override public void sendPacketsTill(int timeMs) { sent.add(timeMs); }
    }
}
