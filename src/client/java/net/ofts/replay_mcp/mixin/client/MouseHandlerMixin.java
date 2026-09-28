package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.ofts.replay_mcp.client.ReplayMCPClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void replayMcp$physicalButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (ReplayMCPClient.suppressConflictingMouse()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void replayMcp$physicalScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ReplayMCPClient.suppressConflictingMouse()) ci.cancel();
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void replayMcp$physicalMove(long window, double x, double y, CallbackInfo ci) {
        if (ReplayMCPClient.suppressConflictingMouse()) ci.cancel();
    }
}
