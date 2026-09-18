package net.ofts.replay_mcp.discovery;

public record InstanceDescriptor(
        String instanceId,
        long processId,
        int port,
        String createdAt,
        String displayName,
        String gameDirectory,
        String protocolVersion,
        String modVersion,
        String minecraftVersion,
        String replayModVersion) { }
