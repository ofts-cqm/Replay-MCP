package net.ofts.replay_mcp.client;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.ofts.replay_mcp.ReplayMCP;
import net.ofts.replay_mcp.action.ActionBatchValidator;
import net.ofts.replay_mcp.artifact.ArtifactStore;
import net.ofts.replay_mcp.audit.AuditWriter;
import net.ofts.replay_mcp.bridge.BridgeRouter;
import net.ofts.replay_mcp.config.ReplayMcpConfig;
import net.ofts.replay_mcp.discovery.DiscoveryPublisher;
import net.ofts.replay_mcp.discovery.InstanceDescriptor;
import net.ofts.replay_mcp.lease.DirectorLeaseManager;
import net.ofts.replay_mcp.operation.OperationRegistry;
import net.ofts.replay_mcp.protocol.IdempotencyStore;
import net.ofts.replay_mcp.protocol.ProtocolLimits;
import net.ofts.replay_mcp.render.RenderPresetRegistry;
import net.ofts.replay_mcp.timeline.TimelineEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReplayMcpServiceGraph implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Path gameDir;
    private final ReplayMcpConfig config;
    private final MinecraftBridgeAdapter adapter;
    private final OperationRegistry operations;
    private final DirectorLeaseManager leases;
    private final AuditWriter audit;
    private final ArtifactStore artifacts;
    private final BridgeWebSocketServer server;
    private final DiscoveryPublisher discovery;

    private ReplayMcpServiceGraph(Path gameDir, ReplayMcpConfig config, MinecraftBridgeAdapter adapter,
                                  OperationRegistry operations, DirectorLeaseManager leases,
                                  AuditWriter audit, ArtifactStore artifacts,
                                  BridgeWebSocketServer server, DiscoveryPublisher discovery) {
        this.gameDir = gameDir; this.config = config; this.adapter = adapter; this.operations = operations; this.leases = leases;
        this.audit = audit; this.artifacts = artifacts; this.server = server; this.discovery = discovery;
    }

    public static ReplayMcpServiceGraph start() throws IOException, InterruptedException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        ReplayMcpConfig config = ReplayMcpConfig.loadOrCreate(gameDir);
        Clock clock = Clock.systemUTC(); ProtocolLimits limits = ProtocolLimits.defaults();
        OperationRegistry operations = new OperationRegistry(clock);
        AuditWriter audit = new AuditWriter(gameDir.resolve(".replay-mcp/audit/audit.jsonl"), 8L * 1024 * 1024, 5, clock, config.auditRedactionPatterns);
        ArtifactStore artifacts = new ArtifactStore(gameDir.resolve(".replay-mcp/artifacts"));
        MinecraftBridgeAdapter adapter = new MinecraftBridgeAdapter(artifacts, config);
        DirectorLeaseManager leases = new DirectorLeaseManager(clock, config.leaseTtl(), config.idleCeiling(), new SecureRandom(), reason -> {
            operations.cancelAll(); adapter.releaseAllInputs();
            JsonObject event = new JsonObject(); event.addProperty("reason", reason.name().toLowerCase());
            try { audit.append("lease.revoked", null, event); } catch (IOException ignored) { }
        });
        RenderPresetRegistry renders = new RenderPresetRegistry(gameDir.resolve("replay_videos"));
        String instanceId = UUID.randomUUID().toString();
        BridgeRouter router = new BridgeRouter(leases, operations, new ActionBatchValidator(limits, config),
                new TimelineEngine(limits, 32), renders, adapter, new IdempotencyStore(2_048), audit, clock, instanceId);
        byte[] token = new byte[32]; new SecureRandom().nextBytes(token);
        BridgeWebSocketServer server = null;
        DiscoveryPublisher discovery = null;
        if (!config.bridgeEnabled) {
            return new ReplayMcpServiceGraph(gameDir, config, adapter, operations, leases, audit, artifacts, null, null);
        }
        server = new BridgeWebSocketServer(token, router, limits);
        int port = server.start();
        String modVersion = FabricLoader.getInstance().getModContainer(ReplayMCP.MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        InstanceDescriptor descriptor = new InstanceDescriptor(instanceId, ProcessHandle.current().pid(), port,
                Instant.now().toString(), "Minecraft " + adapter.status().get("minecraft_version").getAsString(),
                gameDir.toAbsolutePath().normalize().toString(), ReplayMCP.BRIDGE_PROTOCOL, modVersion,
                adapter.status().get("minecraft_version").getAsString(), adapter.status().get("replay_mod_version").getAsString());
        try { discovery = new DiscoveryPublisher(gameDir, descriptor, token); }
        catch (IOException failure) { server.close(); audit.close(); throw failure; }
        return new ReplayMcpServiceGraph(gameDir, config, adapter, operations, leases, audit, artifacts, server, discovery);
    }

    public ReplayMcpConfig config() { return config; }
    public DirectorLeaseManager leases() { return leases; }
    public void emergencyStop() { operations.cancelAll(); adapter.releaseAllInputs(); leases.emergencyStop(); }
    public void releaseInputs() { adapter.releaseAllInputs(); }
    public void humanOverride() { if (config.physicalInputRevokesLease) leases.humanOverride(); }
    public void tick() { leases.status(); adapter.tickActions(); adapter.tickLocalClips(); }
    public void localClipStart() { adapter.localClipStart(); }
    public void localClipEnd() { adapter.localClipEnd(); }
    public void localClipRevoke() { adapter.localClipRevoke(); }
    public void saveConfig() {
        try { config.save(gameDir.resolve("config/replay_mcp.json")); }
        catch (IOException ignored) { }
    }

    public String hudStatus() {
        if (!config.bridgeEnabled || server == null) return "Replay MCP: bridge disabled (restart after enabling)";
        String control = leases.status().map(l -> "directed by " + l.ownerLabel()).orElse("no director");
        return "Replay MCP: " + control + " | " + adapter.localClipHudStatus();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        operations.cancelAll(); adapter.releaseAllInputs();
        if (server != null) server.close();
        if (discovery != null) try { discovery.close(); } catch (IOException ignored) { }
        adapter.close();
        try { audit.close(); } catch (IOException ignored) { }
    }
}
