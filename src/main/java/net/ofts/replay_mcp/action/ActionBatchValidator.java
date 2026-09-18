package net.ofts.replay_mcp.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.config.ReplayMcpConfig;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.protocol.ProtocolLimits;

import java.util.List;
import java.util.regex.Pattern;

public final class ActionBatchValidator {
    private final ProtocolLimits limits;
    private final ReplayMcpConfig config;
    private final List<Pattern> deniedCommands;

    public ActionBatchValidator(ProtocolLimits limits, ReplayMcpConfig config) {
        this.limits = limits;
        this.config = config;
        this.deniedCommands = config.commandDenyPatterns.stream().map(Pattern::compile).toList();
    }

    public JsonArray validate(JsonObject params, boolean flightGranted) {
        if (!params.has("request_id") || params.get("request_id").getAsString().isBlank()) {
            throw invalid("request_id is required");
        }
        if (!params.has("actions") || !params.get("actions").isJsonArray()) throw invalid("actions must be an array");
        JsonArray actions = params.getAsJsonArray("actions");
        if (actions.isEmpty() || actions.size() > limits.maxActions()) throw invalid("action count exceeds limit");
        for (JsonElement value : actions) {
            if (!value.isJsonObject()) throw invalid("each action must be an object");
            JsonObject action = value.getAsJsonObject();
            if (!action.has("kind")) throw invalid("action kind is required");
            ActionKind kind = ActionKind.fromWire(action.get("kind").getAsString());
            int timeout = action.has("timeout_ms") ? action.get("timeout_ms").getAsInt() : 30_000;
            limits.deadline(timeout);
            if (isFlight(kind) && (!config.flightAutomationEnabled || !flightGranted)) {
                throw new BridgeException(BridgeError.POLICY_DENIED, "flight automation is not granted");
            }
            if (kind == ActionKind.EXECUTE_COMMAND) validateCommand(action);
            if (kind == ActionKind.SELECT_HOTBAR_SLOT) {
                int slot = requiredInt(action, "slot");
                if (slot < 1 || slot > 9) throw invalid("hotbar slot must be 1 through 9");
            }
            if (kind == ActionKind.WAIT_TICKS && (requiredInt(action, "ticks") < 0 || requiredInt(action, "ticks") > 12_000)) {
                throw invalid("wait_ticks is out of bounds");
            }
        }
        return actions.deepCopy();
    }

    private void validateCommand(JsonObject action) {
        if (!config.commandsEnabled) throw new BridgeException(BridgeError.POLICY_DENIED, "commands are disabled locally");
        if (!action.has("command")) throw invalid("command is required");
        String command = action.get("command").getAsString();
        if (command.startsWith("/") || command.isBlank() || command.length() > 32_767) {
            throw invalid("command must omit the leading slash");
        }
        if (deniedCommands.stream().anyMatch(p -> p.matcher(command).find())) {
            throw new BridgeException(BridgeError.POLICY_DENIED, "command denied by local policy");
        }
    }

    private static boolean isFlight(ActionKind kind) {
        return switch (kind) {
            case SET_FLYING, FLY_MOVE, FLY_TO, ASCEND, DESCEND, LAND -> true;
            default -> false;
        };
    }

    private static int requiredInt(JsonObject object, String name) {
        if (!object.has(name)) throw invalid(name + " is required");
        return object.get(name).getAsInt();
    }

    private static BridgeException invalid(String message) { return new BridgeException(BridgeError.INVALID_REQUEST, message); }
}
