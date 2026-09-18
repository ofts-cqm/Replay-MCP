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

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void replayMcp$captureCompletedFrame(boolean tick, CallbackInfo ci) {
        FrameCaptureCoordinator.finishFrame(gameRenderer.mainRenderTarget());
    }
}
