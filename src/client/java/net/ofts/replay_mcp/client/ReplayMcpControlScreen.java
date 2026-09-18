package net.ofts.replay_mcp.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ReplayMcpControlScreen extends Screen {
    private final ReplayMcpServiceGraph services;

    ReplayMcpControlScreen(ReplayMcpServiceGraph services) {
        super(Component.literal("Replay MCP Controls")); this.services = services;
    }

    @Override protected void init() {
        int x = width / 2 - 100, y = height / 2 - 105;
        addRenderableWidget(Button.builder(Component.literal("Revoke director lease"), b -> services.leases().humanOverride()).bounds(x, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Release synthetic inputs"), b -> services.releaseInputs()).bounds(x, y + 25, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Emergency stop (F12)"), b -> services.emergencyStop()).bounds(x, y + 50, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Toggle commands (currently " + onOff(services.config().commandsEnabled) + ")"), b -> { services.config().commandsEnabled = !services.config().commandsEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 75, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Toggle flight automation (currently " + onOff(services.config().flightAutomationEnabled) + ")"), b -> { services.config().flightAutomationEnabled = !services.config().flightAutomationEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 100, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Physical input revokes (currently " + onOff(services.config().physicalInputRevokesLease) + ")"), b -> { services.config().physicalInputRevokesLease = !services.config().physicalInputRevokesLease; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 125, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Bridge after restart (currently " + onOff(services.config().bridgeEnabled) + ")"), b -> { services.config().bridgeEnabled = !services.config().bridgeEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 150, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x, y + 180, 200, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 133, 0xFFFFFFFF);
        String status = services.leases().status().map(l -> "Director: " + l.ownerLabel()).orElse("Director: none");
        graphics.centeredText(font, status, width / 2, height / 2 - 119, 0xFFB0B0B0);
    }

    @Override public boolean isPauseScreen() { return false; }
    private static String onOff(boolean value) { return value ? "on" : "off"; }
}
