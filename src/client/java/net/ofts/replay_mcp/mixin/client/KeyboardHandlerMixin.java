package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.ofts.replay_mcp.client.ReplayMCPClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void replayMcp$physicalKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        ReplayMCPClient.physicalInput();
    }
}
