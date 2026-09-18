package net.ofts.replay_mcp.render;

import com.google.gson.JsonObject;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RenderPresetRegistryTest {
    @TempDir Path temp;
    @Test void rejectsArbitraryEncoderAndConfinesOutput() throws Exception {
        RenderPresetRegistry registry = new RenderPresetRegistry(temp);
        JsonObject command = new JsonObject(); command.addProperty("preset", "preview"); command.addProperty("output", "x.mp4"); command.addProperty("ffmpeg", "-anything");
        assertEquals(BridgeError.POLICY_DENIED, assertThrows(BridgeException.class, () -> registry.validate(command)).error());
        JsonObject traversal = new JsonObject(); traversal.addProperty("preset", "preview"); traversal.addProperty("output", "../x.mp4");
        assertEquals(BridgeError.POLICY_DENIED, assertThrows(BridgeException.class, () -> registry.validate(traversal)).error());
        JsonObject safe = new JsonObject(); safe.addProperty("preset", "preview"); safe.addProperty("output", "shots/x.mp4");
        assertTrue(registry.validate(safe).get("valid").getAsBoolean());
        JsonObject legacy = safe.deepCopy(); legacy.addProperty("preset", "preview_720p");
        assertEquals("preview", registry.validate(legacy).get("preset").getAsString());
        JsonObject draft = safe.deepCopy(); draft.addProperty("preset", "draft_360p");
        assertEquals(640, registry.validate(draft).get("width").getAsInt());

        JsonObject unsupportedAa = safe.deepCopy(); unsupportedAa.addProperty("anti_aliasing", 16);
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class, () -> registry.validate(unsupportedAa)).error());
    }
}
