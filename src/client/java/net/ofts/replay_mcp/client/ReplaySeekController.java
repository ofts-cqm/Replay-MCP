package net.ofts.replay_mcp.client;

import com.replaymod.replay.ReplaySender;

final class ReplaySeekController {
    private ReplaySeekController() { }

    static void seek(ReplaySender sender, int targetMs) {
        if (sender.isAsyncMode()) sender.jumpToTime(targetMs);
        else sender.sendPacketsTill(targetMs);
    }

    static void seekSettled(ReplaySender sender, int targetMs, int preRollMs) {
        sender.setSyncModeAndWait();
        sender.sendPacketsTill(Math.max(0, targetMs - preRollMs));
        sender.sendPacketsTill(targetMs);
    }
}
