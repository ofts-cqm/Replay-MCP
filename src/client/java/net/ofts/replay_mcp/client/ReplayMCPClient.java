package net.ofts.replay_mcp.client;

import net.fabricmc.api.ClientModInitializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplayMCPClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Replay MCP");
    private ReplayMcpServiceGraph services;
    private static volatile ReplayMCPClient instance;

    public ReplayMCPClient() { instance = this; }

    public static void physicalInput() {
        ReplayMCPClient current = instance;
        if (current != null && current.services != null) current.services.humanOverride();
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("replay_mcp", "controls"));
        KeyMapping controls = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.controls", InputConstants.Type.KEYSYM, InputConstants.KEY_F10, category));
        KeyMapping emergency = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.emergency_stop", InputConstants.Type.KEYSYM, InputConstants.KEY_F12, category));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                services = ReplayMcpServiceGraph.start();
                LOGGER.info("Replay MCP bridge started");
            } catch (Exception e) {
                LOGGER.error("Unable to start Replay MCP bridge", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (services != null) services.tick();
            while (emergency.consumeClick()) if (services != null) services.emergencyStop();
            while (controls.consumeClick()) if (services != null) client.setScreenAndShow(new ReplayMcpControlScreen(services));
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("replay_mcp", "status"), (graphics, tick) -> {
            if (services == null) return;
            String text = services.hudStatus();
            int width = clientFontWidth(text) + 8;
            graphics.fill(3, 3, 3 + width, 17, 0x99000000);
            graphics.text(net.minecraft.client.Minecraft.getInstance().font, text, 7, 6, 0xFFE0E0E0);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (services != null) services.close(); });
    }

    private static int clientFontWidth(String text) { return net.minecraft.client.Minecraft.getInstance().font.width(text); }
}
