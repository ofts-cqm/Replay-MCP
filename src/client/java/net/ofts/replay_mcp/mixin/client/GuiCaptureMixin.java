package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.ofts.replay_mcp.client.FrameCaptureCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class GuiCaptureMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void replayMcp$hideGuiForCleanCapture(DeltaTracker tracker, boolean renderLevel, boolean renderDebug, CallbackInfo ci) {
        if (FrameCaptureCoordinator.hideGuiForCapture()) ci.cancel();
    }
}
