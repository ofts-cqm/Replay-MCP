package net.ofts.replay_mcp.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LocalClipStateMachineTest {
    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";

    @Test void acceptsAndRevokesSequentialUniqueClips() {
        List<String> ids = new ArrayList<>(List.of(FIRST, SECOND));
        List<String> markers = new ArrayList<>();
        LocalClipStateMachine clips = new LocalClipStateMachine(() -> ids.removeFirst());

        assertEquals(LocalClipStateMachine.Outcome.STARTED, clips.start(true, markers::add).outcome());
        assertEquals(LocalClipStateMachine.Outcome.ACCEPTED, clips.end(true, markers::add).outcome());
        assertEquals(LocalClipStateMachine.Outcome.STARTED, clips.start(true, markers::add).outcome());
        assertEquals(LocalClipStateMachine.Outcome.REVOKED, clips.revoke(true, markers::add).outcome());
        assertEquals(List.of(
                "replay_mcp:clip:v1:" + FIRST + ":start",
                "replay_mcp:clip:v1:" + FIRST + ":end",
                "replay_mcp:clip:v1:" + SECOND + ":start",
                "replay_mcp:clip:v1:" + SECOND + ":revoke"), markers);
    }

    @Test void rejectsInvalidTransitionsAndMissingRecording() {
        List<String> markers = new ArrayList<>();
        LocalClipStateMachine clips = new LocalClipStateMachine(() -> FIRST);
        assertEquals(LocalClipStateMachine.Outcome.REJECTED, clips.start(false, markers::add).outcome());
        assertEquals(LocalClipStateMachine.Outcome.REJECTED, clips.end(true, markers::add).outcome());
        clips.start(true, markers::add);
        assertEquals(LocalClipStateMachine.Outcome.REJECTED, clips.start(true, markers::add).outcome());
        assertTrue(clips.isActive());
        assertEquals(1, markers.size());
    }

    @Test void disconnectLeavesOnlyAnIncompleteStart() {
        List<String> markers = new ArrayList<>();
        LocalClipStateMachine clips = new LocalClipStateMachine(() -> FIRST);
        clips.start(true, markers::add);
        assertEquals(LocalClipStateMachine.Outcome.INTERRUPTED, clips.interrupt().outcome());
        assertFalse(clips.isActive());
        assertEquals(List.of("replay_mcp:clip:v1:" + FIRST + ":start"), markers);
    }

    @Test void parsesOnlyThePrivateVersionedGrammar() {
        assertEquals(ClipMarker.Kind.START, ClipMarker.parse("replay_mcp:clip:v1:" + FIRST + ":start").orElseThrow().kind());
        assertTrue(ClipMarker.parse("_RM_SPLIT").isEmpty());
        assertTrue(ClipMarker.parse("replay_mcp:clip:v2:" + FIRST + ":start").isEmpty());
        assertTrue(ClipMarker.parse("replay_mcp:clip:v1:not-a-uuid:start").isEmpty());
    }
}
