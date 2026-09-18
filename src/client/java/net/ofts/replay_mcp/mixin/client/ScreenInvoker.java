package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("insertText") void replayMcp$insertText(String text, boolean overwrite);
}
