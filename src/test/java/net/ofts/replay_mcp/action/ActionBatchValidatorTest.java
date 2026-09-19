package net.ofts.replay_mcp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.config.ReplayMcpConfig;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionBatchValidatorTest {
    @Test void commandsRequireLocalEnablementAndNoSlash() {
        ReplayMcpConfig config = new ReplayMcpConfig(); ActionBatchValidator disabled = new ActionBatchValidator(ProtocolLimits.defaults(), config);
        JsonObject request = request(action("execute_command")); request.getAsJsonArray("actions").get(0).getAsJsonObject().addProperty("command", "say hi");
        assertEquals(BridgeError.POLICY_DENIED, assertThrows(BridgeException.class, () -> disabled.validate(request, false)).error());
        config.commandsEnabled = true; ActionBatchValidator validator = new ActionBatchValidator(ProtocolLimits.defaults(), config);
        assertEquals(1, validator.validate(request, false).size());
        request.getAsJsonArray("actions").get(0).getAsJsonObject().addProperty("command", "/say hi");
        ActionBatchValidator enabled = validator;
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class, () -> enabled.validate(request, false)).error());
    }

    @Test void flightRequiresMinecraftGrant() {
        ReplayMcpConfig config = new ReplayMcpConfig(); ActionBatchValidator validator = new ActionBatchValidator(ProtocolLimits.defaults(), config);
        assertEquals(BridgeError.POLICY_DENIED, assertThrows(BridgeException.class, () -> validator.validate(request(action("fly_to")), false)).error());
    }

    @Test void navigationRequiresFiniteCoordinatesAndBoundedTolerance() {
        ReplayMcpConfig config = new ReplayMcpConfig(); ActionBatchValidator validator = new ActionBatchValidator(ProtocolLimits.defaults(), config);
        JsonObject navigate = action("navigate_to"); navigate.addProperty("x", 10.5); navigate.addProperty("y", 64); navigate.addProperty("z", -4.5);
        assertEquals(1, validator.validate(request(navigate), false).size());

        navigate.addProperty("tolerance", 0.1);
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class, () -> validator.validate(request(navigate), false)).error());
        navigate.addProperty("tolerance", 1.0); navigate.remove("z");
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class, () -> validator.validate(request(navigate), false)).error());
    }

    private static JsonObject action(String kind) { JsonObject a = new JsonObject(); a.addProperty("kind", kind); return a; }
    private static JsonObject request(JsonObject action) { JsonObject r = new JsonObject(); r.addProperty("request_id", "r"); JsonArray a = new JsonArray(); a.add(action); r.add("actions", a); return r; }
}
