package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.Minecraft;
import net.ofts.replay_mcp.client.FrameCaptureCoordinator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftFrameMixin {
    @Shadow @Final public net.minecraft.client.renderer.GameRenderer gameRenderer;

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void replayMcp$presentReviewWithoutWorldTicks(boolean tick, CallbackInfo ci) {
        net.ofts.replay_mcp.client.ReplayMCPClient.clientFrame();
        FrameCaptureCoordinator.beginFrame(tick && Minecraft.getInstance().level != null);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER))
    private void replayMcp$captureCompletedFrame(boolean tick, CallbackInfo ci) {
        FrameCaptureCoordinator.finishFrame(gameRenderer.mainRenderTarget());
    }
}
