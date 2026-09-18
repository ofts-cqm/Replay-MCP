package net.ofts.replay_mcp.action;

import java.util.Arrays;

public enum ActionKind {
    LOOK, TURN, LOOK_AT_POSITION, LOOK_AT_ENTITY, LOOK_AT_BLOCK,
    MOVE, JUMP, SPRINT, SNEAK, SWIM, STOP_ALL_INPUTS,
    SET_FLYING, FLY_MOVE, FLY_TO, ASCEND, DESCEND, LAND,
    ATTACK, USE, INTERACT_ENTITY, BREAK_BLOCK, PLACE_OR_USE_ITEM, SWING_HAND, PICK_BLOCK,
    SELECT_HOTBAR_SLOT, SWAP_OFFHAND, DROP_ITEM, OPEN_INVENTORY, CLOSE_SCREEN,
    CLICK_SLOT, CLICK_WIDGET, TYPE_TEXT, SUBMIT, CANCEL, RESPAWN,
    MOUNT, DISMOUNT, VEHICLE_INPUT, SEND_CHAT, EXECUTE_COMMAND,
    WAIT_TICKS, WAIT_UNTIL, CHECKPOINT;

    public static ActionKind fromWire(String value) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new net.ofts.replay_mcp.protocol.BridgeException(
                        net.ofts.replay_mcp.protocol.BridgeError.INVALID_REQUEST, "unknown action kind: " + value));
    }
}
