package net.ofts.replay_mcp;

import net.fabricmc.api.ModInitializer;

public class ReplayMCP implements ModInitializer {

    public static final String MOD_ID = "replay_mcp";
    public static final String BRIDGE_PROTOCOL = "replay-mcp.bridge/1";

    @Override
    public void onInitialize() {
        // The service graph is client-owned. Keeping common initialization free of
        // client classes makes dedicated-server rejection deterministic.
    }
}
