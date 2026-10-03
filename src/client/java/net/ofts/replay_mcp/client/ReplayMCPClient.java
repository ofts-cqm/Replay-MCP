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

    private static KeyMapping controlsKey, emergencyKey;
    public static void clientFrame() { ReplayMCPClient c = instance; if (c != null && c.services != null) c.services.presentReviews(); }
    public static boolean suppressConflictingMouse() {
        ReplayMCPClient c = instance;
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        return c != null && c.services != null && c.services.conflictingMouseOperation() && !(screen instanceof ReplayMcpControlScreen) && !(screen instanceof PlayerReviewScreen);
    }
    public static void physicalKey(int action, net.minecraft.client.input.KeyEvent event) {
        if (action != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        ReplayMCPClient c = instance;
        if (c != null && c.services != null) {
            if (emergencyKey != null && emergencyKey.matches(event)) { c.services.emergencyStop(keyTrigger(event)); return; }
            if (controlsKey != null && controlsKey.matches(event)) { c.services.requestReview(); return; }
        }
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        if (screen instanceof ReplayMcpControlScreen || screen instanceof PlayerReviewScreen || (controlsKey != null && controlsKey.matches(event))) return;
        ReplayMCPClient current = instance;
        if (current != null && current.services != null) current.services.humanOverride(keyTrigger(event));
    }

    private static net.ofts.replay_mcp.lease.RevocationTrigger keyTrigger(net.minecraft.client.input.KeyEvent event) {
        var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        String key = InputConstants.getKey(event).getName();
        return new net.ofts.replay_mcp.lease.RevocationTrigger("keyboard_press", key,
                event.key(), event.scancode(), event.modifiers(), screen == null ? "gameplay" : screen.getClass().getName());
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("replay_mcp", "controls"));
        KeyMapping controls = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.controls", InputConstants.Type.KEYSYM, InputConstants.KEY_F10, category));
        controlsKey = controls;
        KeyMapping emergency = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.emergency_stop", InputConstants.Type.KEYSYM, InputConstants.KEY_F12, category));
        emergencyKey = emergency;
        KeyMapping clipStart = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.clip_start", InputConstants.Type.KEYSYM, InputConstants.KEY_F6, category));
        KeyMapping clipEnd = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.clip_end", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, category));
        KeyMapping clipRevoke = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.replay_mcp.clip_revoke", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, category));

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
            while (emergency.consumeClick()) { /* handled immediately at physical callback */ }
            while (controls.consumeClick()) { /* handled immediately at physical callback */ }
            while (clipStart.consumeClick()) if (services != null) services.localClipStart();
            while (clipEnd.consumeClick()) if (services != null) services.localClipEnd();
            while (clipRevoke.consumeClick()) if (services != null) services.localClipRevoke();
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("replay_mcp", "status"), (graphics, tick) -> {
            if (services == null) return;
            String text = services.hudStatus(
                    clipEnd.getTranslatedKeyMessage().getString(),
                    clipRevoke.getTranslatedKeyMessage().getString());
            int width = clientFontWidth(text) + 8;
            graphics.fill(3, 3, 3 + width, 17, 0x99000000);
            graphics.text(net.minecraft.client.Minecraft.getInstance().font, text, 7, 6, 0xFFE0E0E0);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (services != null) services.close(); });
    }

    private static int clientFontWidth(String text) { return net.minecraft.client.Minecraft.getInstance().font.width(text); }
}
