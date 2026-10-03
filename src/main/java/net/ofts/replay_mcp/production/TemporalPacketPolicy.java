package net.ofts.replay_mcp.production;

import java.util.Set;

/** Closed allowlist: unknown packets cannot establish unchanged collision geometry. */
public final class TemporalPacketPolicy {
    public static final String VERSION = "packet-static-world/1";
    private static final Set<String> SAFE = Set.of(
        "KeepAlive", "UpdateTime", "Chat", "EntityEquipment", "UpdateHealth", "ChangeHeldItem",
        "EntityAnimation", "EntityVelocity", "EntityMovement", "EntityPosition", "EntityRotation",
        "EntityPositionRotation", "EntityHeadLook", "SetExperience", "PlaySound", "SpawnParticle",
        "OpenWindow", "CloseWindow", "SetSlot", "WindowItems", "WindowProperty", "ConfirmTransaction",
        "Statistics", "PlayerListEntry", "PlayerListEntryRemove", "TabComplete", "ScoreboardObjective",
        "UpdateScore", "ResetScore", "DisplayScoreboard", "Team", "EntitySoundEffect",
        "UpdateLight", "BlockBreakAnim", "MapData");
    private TemporalPacketPolicy() { }
    public static boolean supported(String packet) { return SAFE.contains(packet); }
}
