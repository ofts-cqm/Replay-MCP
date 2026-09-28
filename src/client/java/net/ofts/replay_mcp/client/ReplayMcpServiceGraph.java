package net.ofts.replay_mcp.client;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.ofts.replay_mcp.ReplayMCP;
import net.ofts.replay_mcp.action.ActionBatchValidator;
import net.ofts.replay_mcp.artifact.ArtifactStore;
import net.ofts.replay_mcp.audit.AuditWriter;
import net.ofts.replay_mcp.bridge.BridgeRouter;
import net.ofts.replay_mcp.config.ReplayMcpConfig;
import net.ofts.replay_mcp.control.PauseOnLostFocusOverride;
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
    private final net.ofts.replay_mcp.production.ProductionReviewStore production;
    private boolean reviewRequested;
    private final net.ofts.replay_mcp.production.ReviewPresentation reviewPresentation;
    private final net.ofts.replay_mcp.production.ReviewPresentation exportPresentation;
    public net.ofts.replay_mcp.production.ProductionReviewStore production() { return production; }
    public void requestReview() { reviewRequested = true; adapter.stopActionsForReview(); presentReviews(); }
    public void presentContract(String project, String hash) {
        var requestedLease = leases.status();
        adapter.stopActionsForReview();
        reviewPresentation.request(project, hash, () -> requestedLease.isPresent()
                && leases.status().map(current -> current.epoch() == requestedLease.get().epoch()).orElse(false));
    }
    public JsonObject productionStatus(String project) {
        JsonObject result = production.get(project);
        result.addProperty("review_submission_version", 1);
        result.add("presentation", reviewPresentation.status(project, result.has("draft_hash") ? result.get("draft_hash").getAsString() : ""));
        result.addProperty("export_review_version", 1);
        result.addProperty("export_decision_version", 1);
        result.add("export_presentation", exportPresentation.status(project, reportBinding(result)));
        return result;
    }
    private static String reportBinding(JsonObject state) {
        return state.has("report") && state.getAsJsonObject("report").has("binding")
                ? state.getAsJsonObject("report").get("binding").getAsString() : "";
    }
    public void presentExportReport(String project) {
        JsonObject state = production.get(project);
        exportPresentation.cancel(project);
        if (!net.ofts.replay_mcp.production.ProductionReviewText.canOverride(state)) return;
        String binding = reportBinding(state);
        var requestedLease = leases.status();
        adapter.stopActionsForReview();
        exportPresentation.request(project, binding, () -> requestedLease.isPresent()
                && leases.status().map(current -> current.epoch() == requestedLease.get().epoch()).orElse(false)
                && binding.equals(reportBinding(production.get(project)))
                && net.ofts.replay_mcp.production.ProductionReviewText.canOverride(production.get(project)));
    }
    /** UI is serviced on real client frames as well as ticks, including paused Replay Mod. */
    public void presentReviews() {
        if (reviewRequested && !adapter.busyForReview()) {
            reviewRequested = false;
            net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ReplayMcpControlScreen(this));
        }
        reviewPresentation.advance();
        exportPresentation.advance();
    }
    public boolean conflictingMouseOperation() { return adapter.conflictingMouseOperation(); }
    public boolean reviewPending() { return reviewRequested || reviewPresentation.pending() || exportPresentation.pending(); }
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
    private final PauseOnLostFocusOverride pauseOnLostFocus = new PauseOnLostFocusOverride();

    private ReplayMcpServiceGraph(Path gameDir, ReplayMcpConfig config, MinecraftBridgeAdapter adapter,
                                  OperationRegistry operations, DirectorLeaseManager leases,
                                  AuditWriter audit, ArtifactStore artifacts,
                                  BridgeWebSocketServer server, DiscoveryPublisher discovery) throws IOException {
        production = new net.ofts.replay_mcp.production.ProductionReviewStore(gameDir.resolve(".replay-mcp/production/reviews.json")); adapter.production(production, this);
        this.gameDir = gameDir; this.config = config; this.adapter = adapter; this.operations = operations; this.leases = leases;
        this.audit = audit; this.artifacts = artifacts; this.server = server; this.discovery = discovery;
        reviewPresentation = new net.ofts.replay_mcp.production.ReviewPresentation(new net.ofts.replay_mcp.production.ReviewPresentation.Surface() {
            public boolean visible(String project, String hash) {
                var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
                return screen instanceof ProductionReviewScreen review && review.displays(project, hash);
            }
            public String busyReason() {
                String active = adapter.reviewBusyReason(); if (active != null) return active;
                var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
                return screen instanceof PlayerReviewScreen || screen instanceof ReplayMcpControlScreen ? "player_review_open" : null;
            }
            public void open(String project, String hash) {
                net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ProductionReviewScreen(ReplayMcpServiceGraph.this, project));
            }
        });
        exportPresentation = new net.ofts.replay_mcp.production.ReviewPresentation(new net.ofts.replay_mcp.production.ReviewPresentation.Surface() {
            public boolean visible(String project, String binding) {
                var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
                return screen instanceof ProductionExportReviewScreen review && review.displays(project, binding);
            }
            public String busyReason() {
                String active = adapter.reviewBusyReason(); if (active != null) return active;
                var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
                return screen instanceof PlayerReviewScreen || screen instanceof ReplayMcpControlScreen ? "player_review_open" : null;
            }
            public void open(String project, String binding) {
                net.minecraft.client.Minecraft.getInstance().setScreenAndShow(new ProductionExportReviewScreen(ReplayMcpServiceGraph.this, project));
            }
        }, "binding");
    }

    public static ReplayMcpServiceGraph start() throws IOException, InterruptedException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        ReplayMcpConfig config = ReplayMcpConfig.loadOrCreate(gameDir);
        Clock clock = Clock.systemUTC(); ProtocolLimits limits = ProtocolLimits.defaults();
        OperationRegistry operations = new OperationRegistry(clock);
        AuditWriter audit = new AuditWriter(gameDir.resolve(".replay-mcp/audit/audit.jsonl"), 8L * 1024 * 1024, 5, clock, config.auditRedactionPatterns);
        ArtifactStore artifacts = new ArtifactStore(gameDir.resolve(".replay-mcp/artifacts"));
        MinecraftBridgeAdapter adapter = new MinecraftBridgeAdapter(artifacts, config);
        DirectorLeaseManager leases = DirectorLeaseManager.withTriggerLogging(clock, config.leaseTtl(), config.idleCeiling(), new SecureRandom(), (reason, trigger) -> {
            operations.cancelAll(); adapter.leaseLost();
            JsonObject event = new JsonObject(); event.addProperty("reason", reason.name().toLowerCase());
            if (trigger != null) event.add("trigger", new com.google.gson.Gson().toJsonTree(trigger));
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
    public void emergencyStop(net.ofts.replay_mcp.lease.RevocationTrigger trigger) { operations.cancelAll(); adapter.releaseAllInputs(); leases.emergencyStop(trigger); }
    public void revokeFromControlScreen(String control, boolean emergency) {
        var trigger = net.ofts.replay_mcp.lease.RevocationTrigger.command("control_screen", control, ReplayMcpControlScreen.class.getName());
        if (emergency) emergencyStop(trigger); else leases.humanOverride(trigger);
    }
    public void releaseInputs() { adapter.releaseAllInputs(); }
    public void humanOverride(net.ofts.replay_mcp.lease.RevocationTrigger trigger) { if (config.physicalInputRevokesLease) leases.humanOverride(trigger); }
    public void tick() {
        boolean controlActive = leases.status().isPresent();
        var options = net.minecraft.client.Minecraft.getInstance().options;
        options.pauseOnLostFocus = pauseOnLostFocus.update(controlActive, options.pauseOnLostFocus);
        adapter.tickActions();
        adapter.tickLocalClips();
        presentReviews();
    }
    public void localClipStart() { adapter.localClipStart(); }
    public void localClipEnd() { adapter.localClipEnd(); }
    public void localClipRevoke() { adapter.localClipRevoke(); }
    public void saveConfig() {
        try { config.save(gameDir.resolve("config/replay_mcp.json")); }
        catch (IOException ignored) { }
    }

    public String hudStatus(String clipEndKey, String clipRevokeKey) {
        if (!config.bridgeEnabled || server == null) return "Replay MCP: bridge disabled (restart after enabling)";
        String control = leases.status().map(l -> "directed by " + l.ownerLabel()).orElse("no director");
        return "Replay MCP: " + control + " | " + adapter.localClipHudStatus(clipEndKey, clipRevokeKey);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        operations.cancelAll(); adapter.releaseAllInputs();
        var options = net.minecraft.client.Minecraft.getInstance().options;
        options.pauseOnLostFocus = pauseOnLostFocus.update(false, options.pauseOnLostFocus);
        if (server != null) server.close();
        if (discovery != null) try { discovery.close(); } catch (IOException ignored) { }
        adapter.close();
        try { audit.close(); } catch (IOException ignored) { }
    }
}
