package net.ofts.replay_mcp.mixin.client;

import com.replaymod.recording.packet.PacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(value = PacketListener.class, remap = false)
public interface PacketListenerAccessor {
    @Accessor("outputPath") Path replayMcp$outputPath();
}
