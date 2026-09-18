package net.ofts.replay_mcp.mode;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.util.EnumSet;

public final class ModeCoordinator {
    private RuntimeMode mode = RuntimeMode.GAME_OFFLINE;

    public synchronized RuntimeMode current() { return mode; }

    public synchronized void reconcile(boolean connected, boolean recording, boolean replay, boolean playing, boolean rendering) {
        if (rendering && replay) mode = RuntimeMode.RENDERING;
        else if (replay && playing) mode = RuntimeMode.REPLAY_PLAYING;
        else if (replay) mode = RuntimeMode.REPLAY_LOADED;
        else if (connected && recording) mode = RuntimeMode.LIVE_RECORDING;
        else if (connected) mode = RuntimeMode.LIVE_IDLE;
        else mode = RuntimeMode.GAME_OFFLINE;
    }

    public synchronized void require(RuntimeMode... permitted) {
        if (!EnumSet.copyOf(java.util.List.of(permitted)).contains(mode)) {
            throw new BridgeException(BridgeError.INVALID_MODE, "operation is unavailable in mode " + mode.name().toLowerCase());
        }
    }
}
