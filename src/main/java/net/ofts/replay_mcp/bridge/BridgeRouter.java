package net.ofts.replay_mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.ReplayMCP;
import net.ofts.replay_mcp.action.ActionBatchValidator;
import net.ofts.replay_mcp.audit.AuditWriter;
import net.ofts.replay_mcp.lease.DirectorLeaseManager;
import net.ofts.replay_mcp.lease.Lease;
import net.ofts.replay_mcp.operation.OperationRegistry;
import net.ofts.replay_mcp.protocol.*;
import net.ofts.replay_mcp.render.RenderPresetRegistry;
import net.ofts.replay_mcp.timeline.TimelineEngine;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;

public final class BridgeRouter {
    private static final Set<String> READ_ONLY = Set.of(
            "system.hello", "system.capabilities", "system.status", "system.health",
            "lease.status", "observation.framebuffer", "observation.motion_burst", "observation.snapshot", "observation.query", "observation.spatial_map",
            "recording.status", "replay.list", "replay.metadata", "timeline.get", "timeline.validate",
            "render.presets", "render.preflight", "action.validate");
    private static final Set<String> LOCAL_ONLY = Set.of("lease.human_revoke");

    private final DirectorLeaseManager leases;
    private final OperationRegistry operations;
    private final ActionBatchValidator actions;
    private final TimelineEngine timeline;
    private final RenderPresetRegistry renders;
    private final BridgeAdapter adapter;
    private final IdempotencyStore idempotency;
    private final AuditWriter audit;
    private final Clock clock;
    private final String instanceId;
    private volatile EventSink events = EventSink.NONE;

    public BridgeRouter(DirectorLeaseManager leases, OperationRegistry operations, ActionBatchValidator actions,
                        TimelineEngine timeline, RenderPresetRegistry renders, BridgeAdapter adapter,
                        IdempotencyStore idempotency, AuditWriter audit, Clock clock, String instanceId) {
        this.leases = leases; this.operations = operations; this.actions = actions; this.timeline = timeline;
        this.renders = renders; this.adapter = adapter; this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.instanceId = instanceId;
    }

    public void eventSink(EventSink sink) { events = sink == null ? EventSink.NONE : sink; adapter.eventSink(events); }

    public JsonElement dispatch(RpcSession session, RpcRequest request) {
        session.requireMethod(request.method());
        if (LOCAL_ONLY.contains(request.method())) throw new BridgeException(BridgeError.POLICY_DENIED, "operation is local-only");
        boolean mutation = !READ_ONLY.contains(request.method()) && !request.method().equals("lease.acquire") && !request.method().equals("lease.heartbeat") && !request.method().equals("lease.release");
        if (mutation) requireLease(session, request.params());
        String requestId = mutation ? requiredString(request.params(), "request_id") : null;
        if (mutation) {
            var cached = idempotency.lookup(requestId, request.params());
            if (cached.isPresent()) return cached.get();
        }
        JsonElement result;
        try {
            result = route(session, request);
            if (mutation) idempotency.remember(requestId, request.params(), result);
            audit(request.method(), requestId, request.params(), null);
            return result;
        } catch (BridgeException failure) {
            audit(request.method() + ".failed", requestId, request.params(), failure.error().wireName());
            throw failure;
        }
    }

    public void disconnected(String connectionId) {
        operations.cancelForConnection(connectionId);
        leases.disconnected(connectionId);
    }

    private JsonElement route(RpcSession session, RpcRequest request) {
        JsonObject p = request.params();
        return switch (request.method()) {
            case "system.hello" -> hello(session, p);
            case "system.capabilities" -> adapter.capabilities();
            case "system.status" -> status();
            case "system.health" -> object("healthy", true);
            case "system.emergency_stop" -> emergencyStop();
            case "system.cancel" -> cancel(requiredString(p, "operation_id"));
            case "lease.status" -> leaseStatus();
            case "lease.acquire" -> leaseJson(leases.acquire(session.connectionId(), requiredString(p, "owner_label")));
            case "lease.heartbeat" -> leaseJson(leases.heartbeat(session.connectionId(), requiredString(p, "lease_id"), requiredLong(p, "fence"), p.has("active") && p.get("active").getAsBoolean()));
            case "lease.release" -> release(session, p);
            case "action.validate" -> actions.validate(p, adapter.flightGranted());
            case "action.release_inputs" -> releaseInputs();
            case "action.cancel_batch" -> cancel(requiredString(p, "operation_id"));
            case "render.cancel" -> adapter.invoke("render.cancel", p, new net.ofts.replay_mcp.operation.CancellationToken());
            case "timeline.get" -> timeline.get();
            case "timeline.validate" -> timeline.validate(requiredArray(p, "operations"));
            case "timeline.apply" -> applyTimeline(p);
            case "timeline.undo" -> undoTimeline(p);
            case "render.presets" -> renders.list();
            case "render.preflight" -> renders.validate(p);
            default -> invokeAdapter(session, request);
        };
    }

    private JsonElement invokeAdapter(RpcSession session, RpcRequest request) {
        if (!knownFamily(request.method())) throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown bridge method");
        OperationRegistry.Operation operation = operations.begin(request.id(), session.connectionId(), request.method(), clock.instant().plus(request.deadline()));
        try {
            operations.check(operation);
            if (request.method().equals("action.start_batch")) actions.validate(request.params(), adapter.flightGranted());
            if (request.method().equals("render.start") || request.method().equals("render.still")) renders.validate(request.params());
            JsonElement result = adapter.invoke(request.method(), request.params(), operation.token());
            if (request.method().equals("observation.spatial_map") && result.isJsonObject()) {
                result.getAsJsonObject().addProperty("instance_id", instanceId);
            }
            return result;
        } finally {
            operations.complete(operation.id());
        }
    }

    private JsonObject hello(RpcSession session, JsonObject params) {
        if (!ReplayMCP.BRIDGE_PROTOCOL.equals(requiredString(params, "protocol"))) {
            throw new BridgeException(BridgeError.PROTOCOL_MISMATCH, "supported protocol is " + ReplayMCP.BRIDGE_PROTOCOL);
        }
        if (!instanceId.equals(requiredString(params, "instance_id"))) {
            throw new BridgeException(BridgeError.PROTOCOL_MISMATCH, "instance identity does not match this process");
        }
        session.completeHello();
        JsonObject result = status(); result.addProperty("protocol", ReplayMCP.BRIDGE_PROTOCOL); result.addProperty("instance_id", instanceId); result.addProperty("process_id", ProcessHandle.current().pid()); result.addProperty("connection_id", session.connectionId()); return result;
    }

    private JsonObject status() {
        JsonObject result = adapter.status().deepCopy();
        result.add("capabilities", adapter.capabilities());
        result.add("lease", leaseStatus());
        result.addProperty("time_precision_us", 1_000);
        return result;
    }

    private JsonObject emergencyStop() {
        operations.cancelAll(); adapter.releaseAllInputs(); leases.emergencyStop();
        events.publish("emergency_stop", new JsonObject()); return object("stopped", true);
    }

    private JsonObject release(RpcSession session, JsonObject params) {
        leases.release(session.connectionId(), requiredString(params, "lease_id"), requiredLong(params, "fence"));
        return object("released", true);
    }

    private JsonObject releaseInputs() { adapter.releaseAllInputs(); return object("released", true); }
    private JsonObject cancel(String id) { return object("cancelled", operations.cancel(id)); }

    private JsonObject applyTimeline(JsonObject params) {
        JsonObject result = timeline.apply(requiredString(params, "base_revision"), requiredArray(params, "operations"));
        try { adapter.timelineChanged(result); return result; }
        catch (RuntimeException failure) {
            timeline.undo(result.get("undo_token").getAsString(), result.get("revision").getAsString());
            throw failure;
        }
    }

    private JsonObject undoTimeline(JsonObject params) {
        JsonObject result = timeline.undo(requiredString(params, "undo_token"), requiredString(params, "base_revision"));
        adapter.timelineChanged(result); return result;
    }

    private JsonObject leaseStatus() {
        JsonObject result = new JsonObject(); result.addProperty("epoch", leases.epoch());
        leases.status().ifPresentOrElse(lease -> { result.addProperty("held", true); result.addProperty("owner_label", lease.ownerLabel()); result.addProperty("acquired_at", lease.acquiredAt().toString()); result.addProperty("last_active_at", lease.lastActiveAt().toString()); result.addProperty("expires_at", lease.expiresAt().toString()); }, () -> result.addProperty("held", false));
        return result;
    }

    private static JsonObject leaseJson(Lease lease) {
        JsonObject result = new JsonObject(); result.addProperty("lease_id", lease.id()); result.addProperty("fence", lease.epoch()); result.addProperty("owner_label", lease.ownerLabel()); result.addProperty("expires_at", lease.expiresAt().toString()); return result;
    }

    private void requireLease(RpcSession session, JsonObject params) { leases.require(session.connectionId(), requiredString(params, "lease_id"), requiredLong(params, "fence")); }

    private void audit(String type, String requestId, JsonObject params, String failure) {
        JsonObject details = params.deepCopy(); if (failure != null) details.addProperty("error", failure);
        try { audit.append(type, requestId, details); } catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "audit write failed"); }
    }

    private static boolean knownFamily(String method) { return method.matches("(observation|action|recording|replay|timeline|render)\\.[a-z_]+") && !method.contains(".."); }
    private static JsonObject object(String name, boolean value) { JsonObject result = new JsonObject(); result.addProperty(name, value); return result; }
    private static String requiredString(JsonObject o, String key) { if (!o.has(key) || !o.get(key).isJsonPrimitive() || o.get(key).getAsString().isBlank()) throw new BridgeException(BridgeError.INVALID_REQUEST, key + " is required"); return o.get(key).getAsString(); }
    private static long requiredLong(JsonObject o, String key) { if (!o.has(key)) throw new BridgeException(BridgeError.INVALID_REQUEST, key + " is required"); try { return o.get(key).getAsLong(); } catch (RuntimeException e) { throw new BridgeException(BridgeError.INVALID_REQUEST, key + " must be an integer"); } }
    private static JsonArray requiredArray(JsonObject o, String key) { if (!o.has(key) || !o.get(key).isJsonArray()) throw new BridgeException(BridgeError.INVALID_REQUEST, key + " must be an array"); return o.getAsJsonArray(key); }
}
