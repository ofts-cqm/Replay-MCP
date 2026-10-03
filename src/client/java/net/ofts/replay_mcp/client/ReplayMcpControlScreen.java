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
        int x = 15, y = 48, column = (width - 35) / 2, right = x + column + 5;
        addRenderableWidget(Button.builder(Component.literal("Revoke director control"), b -> services.revokeFromControlScreen("revoke_director_control", false)).bounds(x, y, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Release held inputs"), b -> services.releaseInputs()).bounds(right, y, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Emergency stop (F12)"), b -> services.revokeFromControlScreen("emergency_stop", true)).bounds(x, y + 25, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Allow commands: " + onOff(services.config().commandsEnabled)), b -> { services.config().commandsEnabled = !services.config().commandsEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(right, y + 25, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Flight automation: " + onOff(services.config().flightAutomationEnabled)), b -> { services.config().flightAutomationEnabled = !services.config().flightAutomationEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 50, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Keyboard takeover: " + onOff(services.config().physicalInputRevokesLease)), b -> { services.config().physicalInputRevokesLease = !services.config().physicalInputRevokesLease; services.saveConfig(); rebuildWidgets(); }).bounds(right, y + 50, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Bridge after restart: " + onOff(services.config().bridgeEnabled)), b -> { services.config().bridgeEnabled = !services.config().bridgeEnabled; services.saveConfig(); rebuildWidgets(); }).bounds(x, y + 75, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Review video plans"), b -> minecraft.setScreenAndShow(new ProductionReviewScreen(services))).bounds(x, y + 100, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Review export results"), b -> minecraft.setScreenAndShow(new ProductionExportReviewScreen(services))).bounds(right, y + 100, column, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x, y + 125, width - 30, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
        String status = services.leases().status().map(l -> "Director: " + l.ownerLabel()).orElse("Director: none");
        graphics.centeredText(font, status, width / 2, 30, 0xFFB0B0B0);
    }

    @Override public boolean isPauseScreen() { return false; }
    private static String onOff(boolean value) { return value ? "on" : "off"; }
}
