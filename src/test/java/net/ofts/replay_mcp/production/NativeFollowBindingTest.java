package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import com.replaymod.pathing.properties.TimestampProperty;
import com.replaymod.simplepathing.InterpolatorType;
import com.replaymod.simplepathing.SPTimeline;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeFollowBindingTest {
    private static final String PLAYER = "07d6a5f4-54b2-361e-b510-a0120166f400";
    private static final String SESSION = "isolated-replay-working-copy";

    private void rebuild(SPTimeline timeline) {
        timeline.setDefaultInterpolatorType(InterpolatorType.LINEAR);
        timeline.getTimePath().getKeyframes().stream().toList().forEach(k -> timeline.getTimePath().remove(k, true));
        timeline.getPositionPath().getKeyframes().stream().toList().forEach(k -> timeline.getPositionPath().remove(k, true));
        timeline.addTimeKeyframe(0, 151250);
        timeline.addTimeKeyframe(1000, 152250);
        for (int time = 0; time <= 1000; time += 50)
            timeline.addPositionKeyframe(time, 230, 166, 221, 0, 0, 0, -1);
        for (int time = 0; time < 1000; time += 50)
            timeline.setInterpolator(time, InterpolatorType.LINEAR.newInstance());
    }

    private JsonObject trace(int fps) {
        var trace = new JsonObject();
        trace.addProperty("player_uuid", PLAYER);
        trace.addProperty("follow_fps", fps);
        trace.addProperty("replay_session", SESSION);
        trace.addProperty("source_start_us", 151250000L);
        trace.addProperty("source_end_us", 152250000L);
        return trace;
    }

    private JsonObject params(int fps, boolean skip) {
        var params = new JsonObject();
        params.addProperty("follow_player_uuid", PLAYER);
        params.addProperty("follow_fps", fps);
        params.addProperty("skip_collision_check", skip);
        return params;
    }

    @Test void acceptsMatchingTraceImmediatelyAfterFreshAndPreviouslyValidatedPathsAreRebuilt() {
        for (int fps : new int[]{20, 60, 120}) for (boolean skip : new boolean[]{false, true}) {
            var timeline = new SPTimeline();
            for (int attempt = 0; attempt < 2; attempt++) {
                rebuild(timeline);
                assertNull(timeline.getEntityTracker());
                assertEquals(-1, timeline.getTimePath().getValue(TimestampProperty.PROPERTY, 0).orElse(-1));
                // Calls the production guard directly, without prewarming interpolation with eligibility.
                assertTrue(NativeClearancePolicy.followBindingMatches(timeline, 0, 1000, trace(fps), params(fps, skip), SESSION));
                assertEquals(151250, timeline.getTimePath().getValue(TimestampProperty.PROPERTY, 0).orElse(-1));
            }
        }
    }

    @Test void rejectsMissingStaleOrMismatchedEvidenceEvenWhenCollisionIsSkipped() {
        for (boolean skip : new boolean[]{false, true}) {
            var timeline = new SPTimeline(); rebuild(timeline);
            var params = params(60, skip);
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 1000, null, params, SESSION));
            for (String field : new String[]{"player_uuid", "replay_session", "follow_fps", "source_start_us", "source_end_us"}) {
                var stale = trace(60);
                switch (field) {
                    case "player_uuid", "replay_session" -> stale.addProperty(field, "stale");
                    case "follow_fps" -> stale.addProperty(field, 20);
                    default -> stale.addProperty(field, stale.get(field).getAsLong() + 1000);
                }
                assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 1000, stale, params, SESSION), field);
            }
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 50, 1000, trace(60), params, SESSION));
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 950, trace(60), params, SESSION));
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 1050, trace(60), params, SESSION));
            timeline.addTimeKeyframe(500, 151500);
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 1000, trace(60), params, SESSION));
            rebuild(timeline);
            timeline.setInterpolator(0, InterpolatorType.CUBIC.newInstance());
            assertFalse(NativeClearancePolicy.followBindingMatches(timeline, 0, 1000, trace(60), params, SESSION));
        }
    }
}
