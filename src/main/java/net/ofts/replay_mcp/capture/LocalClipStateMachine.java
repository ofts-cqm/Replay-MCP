package net.ofts.replay_mcp.capture;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One-active-clip state machine. Replay Mod integration and UI feedback stay at the client boundary. */
public final class LocalClipStateMachine {
    @FunctionalInterface
    public interface MarkerWriter { void write(String markerName); }

    public enum Outcome { STARTED, ACCEPTED, REVOKED, INTERRUPTED, REJECTED }
    public record Transition(Outcome outcome, String clipId, String message) { }

    private final Supplier<String> ids;
    private String activeClipId;
    private Transition lastTransition = new Transition(Outcome.REJECTED, null, "no clip selected");

    public LocalClipStateMachine() { this(() -> UUID.randomUUID().toString()); }

    public LocalClipStateMachine(Supplier<String> ids) { this.ids = Objects.requireNonNull(ids); }

    public synchronized Transition start(boolean recordingAvailable, MarkerWriter writer) {
        if (!recordingAvailable) return remember(Outcome.REJECTED, null, "Replay Mod recording is not armed");
        if (activeClipId != null) return remember(Outcome.REJECTED, activeClipId, "a clip is already active");
        String clipId = UUID.fromString(ids.get()).toString();
        writer.write(new ClipMarker(clipId, ClipMarker.Kind.START).markerName());
        activeClipId = clipId;
        return remember(Outcome.STARTED, clipId, "clip started");
    }

    public synchronized Transition end(boolean recordingAvailable, MarkerWriter writer) {
        if (activeClipId == null) return remember(Outcome.REJECTED, null, "no clip is active");
        if (!recordingAvailable) return interrupt();
        String clipId = activeClipId;
        writer.write(new ClipMarker(clipId, ClipMarker.Kind.END).markerName());
        activeClipId = null;
        return remember(Outcome.ACCEPTED, clipId, "clip accepted");
    }

    public synchronized Transition revoke(boolean recordingAvailable, MarkerWriter writer) {
        if (activeClipId == null) return remember(Outcome.REJECTED, null, "no clip is active");
        if (!recordingAvailable) return interrupt();
        String clipId = activeClipId;
        writer.write(new ClipMarker(clipId, ClipMarker.Kind.REVOKE).markerName());
        activeClipId = null;
        return remember(Outcome.REVOKED, clipId, "clip revoked");
    }

    public synchronized Transition interrupt() {
        if (activeClipId == null) return remember(Outcome.REJECTED, null, "no clip is active");
        String clipId = activeClipId;
        activeClipId = null;
        return remember(Outcome.INTERRUPTED, clipId, "clip interrupted; its start marker remains incomplete");
    }

    public synchronized boolean isActive() { return activeClipId != null; }
    public synchronized String activeClipId() { return activeClipId; }
    public synchronized Transition lastTransition() { return lastTransition; }

    private Transition remember(Outcome outcome, String clipId, String message) {
        return lastTransition = new Transition(outcome, clipId, message);
    }
}
