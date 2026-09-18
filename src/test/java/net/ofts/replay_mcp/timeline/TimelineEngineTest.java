package net.ofts.replay_mcp.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimelineEngineTest {
    @Test void appliesAtomicallyNormalizesTimeAndSupportsUndo() {
        TimelineEngine engine = new TimelineEngine(ProtocolLimits.defaults(), 4); String base = engine.get().get("revision").getAsString();
        JsonObject upsert = new JsonObject(); upsert.addProperty("op", "upsert_keyframe"); upsert.addProperty("track", "yaw"); upsert.addProperty("time_us", 1499); JsonObject keyframe = new JsonObject(); keyframe.addProperty("value", 90); upsert.add("keyframe", keyframe);
        JsonArray operations = new JsonArray(); operations.add(upsert);
        JsonObject applied = engine.apply(base, operations); assertEquals(1000, applied.getAsJsonObject("tracks").getAsJsonArray("yaw").get(0).getAsJsonObject().get("time_us").getAsLong());
        String token = applied.get("undo_token").getAsString(); String revision = applied.get("revision").getAsString();
        assertNotEquals(base, revision); assertEquals(base, engine.undo(token, revision).get("revision").getAsString());
    }

    @Test void staleRevisionRollsBackAndUnsupportedTrackIsHonest() {
        TimelineEngine engine = new TimelineEngine(ProtocolLimits.defaults(), 4); JsonObject op = new JsonObject(); op.addProperty("op", "replace_track"); op.addProperty("track", "fov"); op.add("keyframes", new JsonArray()); JsonArray operations = new JsonArray(); operations.add(op);
        assertEquals(BridgeError.CAPABILITY_UNAVAILABLE, assertThrows(BridgeException.class, () -> engine.apply(engine.get().get("revision").getAsString(), operations)).error());
        assertEquals(BridgeError.CONFLICT, assertThrows(BridgeException.class, () -> engine.apply("stale", operations)).error());
    }
}
