import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { McpServer, type ToolAnnotations } from "@modelcontextprotocol/server";
import { z } from "zod";
import { BridgeRpcError } from "../bridge/client.js";
import { SidecarError } from "../bridge/discovery.js";
import type { ArtifactRecord } from "../artifacts/store.js";
import type { JobRecord } from "../jobs/store.js";
import { ReplayMcpRuntime, objectResult } from "../runtime.js";
import { SIDECAR_VERSION } from "../types.js";

const instance = { instance_id: z.uuid().optional().describe("Target instance; omit only when exactly one instance is live") };
const loose = z.object(instance).catchall(z.unknown());
const requestId = z.string().min(1).max(128).optional();
const timeout = z.number().int().min(250).max(300_000).optional();

const READ: ToolAnnotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const GAME_READ: ToolAnnotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: true };
const MUTATE: ToolAnnotations = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true };
const PROJECT_MUTATE: ToolAnnotations = { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false };

type ToolContent =
  | { type: "text"; text: string }
  | { type: "image"; data: string; mimeType: string }
  | { type: "resource_link"; uri: string; name: string; mimeType?: string; description?: string };
type ToolResult = { content: ToolContent[]; structuredContent: Record<string, unknown>; isError?: boolean };

export function registerTools(server: McpServer, runtime: ReplayMcpRuntime): void {
  server.registerTool("system_status", {
    title: "Replay MCP system status",
    description: "Report sidecar configuration, live Minecraft instances, capabilities, policies, runtime modes, and active jobs.",
    inputSchema: z.object({ instance_id: z.uuid().optional() }), annotations: READ,
  }, safe(async ({ instance_id }) => {
    await runtime.discovery.discoverOnce();
    const clients = instance_id ? [runtime.client(instance_id)] : [...runtime.discovery.clients.values()].filter((client) => client.connected);
    const instances = await Promise.all(clients.map(async (client) => {
      runtime.jobs.attachBridgeEvents(client);
      const status = objectResult(await client.call("system.status"));
      return { descriptor: client.descriptor, ...status, sidecar_owns_control: runtime.leases.get(client.descriptor.instanceId) !== undefined };
    }));
    return ok(`${instances.length} live Replay MCP instance${instances.length === 1 ? "" : "s"}.`, {
      sidecar_version: SIDECAR_VERSION,
      bridge_protocol: "replay-mcp.bridge/1",
      session_id: runtime.sessionId,
      audit_resource: `replay-mcp://audit/${runtime.sessionId}`,
      state: instances.length ? "online" : "offline",
      searched_game_dirs: runtime.discovery.gameDirs,
      game_dir_sources: runtime.discovery.gameDirSources,
      discovery_diagnostics: runtime.discovery.diagnostics,
      guessed_game_dirs: runtime.options.guessedGameDirs,
      data_dir: runtime.options.dataDir,
      instances,
      active_jobs: runtime.jobs.list().filter((job) => ["queued", "running"].includes(job.status)),
    });
  }));

  server.registerTool("control_status", {
    title: "Director control status", description: "Read the authoritative director lease without exposing its secret.",
    inputSchema: z.object(instance), annotations: READ,
  }, safe(async (args) => {
    const { result, client } = await runtime.read("lease.status", args);
    return ok("Director lease status read.", { instance_id: client.descriptor.instanceId, ...objectResult(result), sidecar_owns_control: runtime.leases.get(client.descriptor.instanceId) !== undefined });
  }));

  server.registerTool("control_acquire", {
    title: "Acquire director control", description: "Acquire the exclusive, expiring Minecraft director lease. Never steals or queues control.",
    inputSchema: z.object({ ...instance, owner_label: z.string().min(1).max(64).optional() }), annotations: MUTATE,
  }, safe(async ({ instance_id, owner_label }) => {
    const client = runtime.client(instance_id);
    const lease = await runtime.leases.acquire(client, owner_label);
    return ok(`Director control acquired for ${client.descriptor.displayName}.`, { instance_id: client.descriptor.instanceId, ...lease });
  }));

  server.registerTool("control_release", {
    title: "Release director control", description: "Cancel active input, release held controls, and relinquish this session's lease.",
    inputSchema: z.object(instance), annotations: MUTATE,
  }, safe(async ({ instance_id }) => {
    const client = runtime.client(instance_id);
    const result = await runtime.leases.release(client);
    return ok("Director control released.", { instance_id: client.descriptor.instanceId, ...result });
  }));

  server.registerTool("job_list", {
    title: "List Replay MCP jobs", description: "List persistent render, still, preview, analysis, and export jobs.",
    inputSchema: z.object({ status: z.enum(["queued", "running", "completed", "failed", "cancelled"]).optional(), project_id: z.string().optional() }), annotations: READ,
  }, safe(async ({ status, project_id }) => {
    const jobs = runtime.jobs.list().filter((job) => (!status || job.status === status) && (!project_id || job.project_id === project_id));
    return ok(`${jobs.length} job${jobs.length === 1 ? "" : "s"}.`, { jobs });
  }));

  server.registerTool("job_get", {
    title: "Get a Replay MCP job", description: "Return persistent progress, timestamps, warnings, results, and failure details.",
    inputSchema: z.object({ job_id: z.uuid() }), annotations: READ,
  }, safe(async ({ job_id }) => {
    const job = runtime.jobs.get(job_id);
    if (!job) throw new SidecarError("job_not_found", `job ${job_id} was not found`);
    return ok(`Job is ${job.status}.`, { job, log_resource: `replay-mcp://job/${job.id}/log` });
  }));

  server.registerTool("job_cancel", {
    title: "Cancel a Replay MCP job", description: "Safely request cancellation. Bridge-backed cancellation requires this session's director lease.",
    inputSchema: z.object({ job_id: z.uuid() }), annotations: MUTATE,
  }, safe(async ({ job_id }, signal) => {
    const job = runtime.jobs.get(job_id);
    if (!job) throw new SidecarError("job_not_found", `job ${job_id} was not found`);
    if (!job.cancellable || !["queued", "running"].includes(job.status)) return ok(`Job is ${job.status}.`, { job });
    let updated: JobRecord;
    if (job.bridge_backed && job.instance_id && job.bridge_job_id) {
      const { result } = await runtime.mutate("render.cancel", { instance_id: job.instance_id, job_id: job.bridge_job_id }, signal);
      updated = await runtime.jobs.update(job.id, { status: "cancelled", result: objectResult(result) });
    } else updated = await runtime.jobs.cancelSidecar(job);
    return ok(`Job is ${updated.status}.`, { job: updated });
  }));

  server.registerTool("artifact_list", {
    title: "List verified artifacts", description: "List checksum-verified screenshots, replays, previews, renders, stills, manifests, and logs.",
    inputSchema: z.object({
      project_id: z.string().optional(), scene_id: z.string().optional(), shot_id: z.string().optional(), take_id: z.string().optional(),
      mime_type: z.string().optional(), created_after: z.iso.datetime().optional(),
    }), annotations: READ,
  }, safe(async (filter) => {
    const artifacts = runtime.artifacts.list().filter((artifact) =>
      (!filter.project_id || artifact.project_id === filter.project_id) &&
      (!filter.scene_id || artifact.scene_id === filter.scene_id) &&
      (!filter.shot_id || artifact.shot_id === filter.shot_id) &&
      (!filter.take_id || artifact.take_id === filter.take_id) &&
      (!filter.mime_type || artifact.mime_type === filter.mime_type) &&
      (!filter.created_after || artifact.created_at >= filter.created_after));
    return ok(`${artifacts.length} verified artifact${artifacts.length === 1 ? "" : "s"}.`, { artifacts: artifacts.map(withResourceUri) });
  }));

  server.registerTool("game_observe", {
    title: "Observe the live game", description: "Capture one checksum-verified image and its synchronized structured Minecraft snapshot.",
    inputSchema: z.object({
      ...instance, view: z.enum(["player", "clean", "annotated"]).default("player"), include: z.array(z.string()).optional(),
      max_width: z.number().int().positive().max(7680).optional(), max_height: z.number().int().positive().max(4320).optional(),
      quality: z.number().int().min(1).max(100).optional(), nearby_distance: z.number().positive().max(256).optional(),
      limit: z.number().int().positive().max(4096).optional(), persist: z.boolean().default(true),
    }), annotations: GAME_READ,
  }, safe(async (args) => {
    const { result, client } = await runtime.read("observation.framebuffer", args);
    const frame = objectResult(result);
    const [artifact] = await runtime.registerArtifacts(frame, client, { provenance: { tool: "game_observe" } }, args.persist);
    if (!artifact) throw new SidecarError("artifact_invalid", "bridge framebuffer response did not contain an artifact descriptor");
    const image = await runtime.artifacts.inlineImage(artifact);
    const snapshot = isObject(frame.snapshot) ? frame.snapshot : objectResult(await client.call("observation.snapshot", {}));
    return okWith(`Observed ${client.descriptor.displayName} at tick ${String(frame.capture_tick ?? snapshot.tick ?? "unknown")}.`, {
      instance_id: client.descriptor.instanceId, frame_id: artifact.id, artifact: artifactSummary(artifact, args.persist),
      capture_tick: frame.capture_tick ?? snapshot.tick, view: frame.view, annotations: frame.annotations ?? [], snapshot,
    }, [image]);
  }));

  server.registerTool("game_observe_motion", {
    title: "Observe game motion", description: "Capture a bounded visual burst as verified frames or a sidecar-built contact sheet.",
    inputSchema: z.object({
      ...instance, view: z.enum(["player", "clean", "annotated"]).default("clean"), frames: z.number().int().min(1).max(64).default(8),
      interval_ms: z.number().int().min(16).max(10_000).optional(), quality: z.number().int().min(1).max(100).optional(),
      output_mode: z.enum(["contact_sheet", "frames"]).default("contact_sheet"),
    }), annotations: GAME_READ,
  }, safe(async (args) => {
    const { result, client } = await runtime.read("observation.motion_burst", args);
    const data = objectResult(result);
    const artifacts = await runtime.registerArtifacts(data, client, { provenance: { tool: "game_observe_motion" } });
    if (args.output_mode === "contact_sheet" && artifacts.length) {
      const sheet = await buildContactSheet(runtime, artifacts, `motion-${crypto.randomUUID()}`);
      return okWith(`Captured ${artifacts.length} motion frames; ${String(data.dropped_frames ?? 0)} dropped.`, {
        ...data, frames: artifacts.map(withResourceUri), contact_sheet: withResourceUri(sheet),
      }, [await runtime.artifacts.inlineImage(sheet)]);
    }
    const inline = await Promise.all(artifacts.slice(0, 16).map(async (item) => await runtime.artifacts.inlineImage(item)));
    return okWith(`Captured ${artifacts.length} motion frames; ${String(data.dropped_frames ?? 0)} dropped.`, { ...data, frames: artifacts.map(withResourceUri) }, inline);
  }));

  server.registerTool("game_query", {
    title: "Query structured game state", description: "Read precise bounded state without capturing another image or loading distant chunks.",
    inputSchema: z.object({
      ...instance, kind: z.enum(["player", "world", "entities", "blocks", "inventory", "screen", "scoreboard", "chat_or_system_messages", "target"]),
      bounds: z.record(z.string(), z.number()).optional(), distance: z.number().positive().max(256).optional(), type: z.string().optional(),
      name: z.string().optional(), tags: z.array(z.string()).optional(), observation_ids: z.array(z.string()).optional(), limit: z.number().int().min(1).max(4096).optional(),
    }), annotations: GAME_READ,
  }, safe(async (args) => {
    const { result, client } = await runtime.read("observation.query", args);
    return ok("Game state queried.", { instance_id: client.descriptor.instanceId, kind: args.kind, result: projectGameQuery(objectResult(result), args) });
  }));

  server.registerTool("game_perform", {
    title: "Perform normal game actions", description: "Execute an audited ordered action batch with guaranteed synthetic-input release.",
    inputSchema: z.object({
      ...instance, request_id: requestId, actions: z.array(z.record(z.string(), z.unknown())).min(1).max(128),
      on_failure: z.enum(["stop", "continue"]).default("stop"), capture: z.enum(["none", "after", "checkpoints", "after_and_on_failure"]).default("none"),
      timeout_ms: timeout,
    }), annotations: MUTATE,
  }, safe(async (args, signal) => await performWithCapture(runtime, args, signal)));

  server.registerTool("recording_status", {
    title: "Recording status", description: "Read Replay Mod recording and logical-take state.", inputSchema: z.object(instance), annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "recording.status", args, "Recording status read.")));

  server.registerTool("recording_start", {
    title: "Start logical take", description: "Start a logical take in the connection-scoped Replay Mod recording.",
    inputSchema: z.object({ ...instance, request_id: requestId, project_id: z.string().optional(), scene_id: z.string().optional(), take_id: z.string().optional(), metadata: z.record(z.string(), z.unknown()).optional() }), annotations: MUTATE,
  }, safe(async (args, signal) => {
    const { result, client } = await runtime.mutate("recording.start", args, signal);
    const data = objectResult(result);
    runtime.recordingContexts.set(client.descriptor.instanceId, {
      ...(args.project_id ? { project_id: args.project_id } : {}),
      ...(args.scene_id ? { scene_id: args.scene_id } : {}),
      ...(typeof data.take_id === "string" ? { take_id: data.take_id } : args.take_id ? { take_id: args.take_id } : {}),
    });
    return ok("Logical take started.", { instance_id: client.descriptor.instanceId, result: data });
  }));

  server.registerTool("recording_stop", {
    title: "Stop logical take", description: "Add the take split marker and report pending connection-scoped replay finalization honestly.",
    inputSchema: z.object({ ...instance, request_id: requestId }), annotations: MUTATE,
  }, safe(async (args, signal) => {
    const { result, client } = await runtime.mutate("recording.stop", args, signal);
    const data = objectResult(result);
    const context = runtime.recordingContexts.get(client.descriptor.instanceId) ?? {};
    runtime.recordingContexts.delete(client.descriptor.instanceId);
    let job: JobRecord | undefined;
    if (data.status === "pending_finalization") {
      job = await runtime.jobs.create("analysis", {
        cancellable: false,
        instance_id: client.descriptor.instanceId,
        ...(context.project_id ? { project_id: context.project_id } : {}),
        result: { purpose: "recording_finalization", ...context, take_id: data.take_id ?? context.take_id },
      });
    }
    return ok("Logical take stopped; Replay Mod file finalization remains connection-scoped.", {
      instance_id: client.descriptor.instanceId, result: data, ...(job ? { finalization_job: job } : {}),
    });
  }));

  server.registerTool("recording_finalize_and_open", {
    title: "Finalize and open recording",
    description: "Intentionally disconnect the current world, wait for the connection-scoped replay to finalize, and open an immutable working copy.",
    inputSchema: z.object({ ...instance, request_id: requestId, finalization_job_id: z.uuid(), timeout_ms: z.number().int().min(5_000).max(300_000).default(120_000) }), annotations: MUTATE,
  }, safe(async (args, signal) => {
    const pending = runtime.jobs.get(args.finalization_job_id);
    if (!pending || pending.kind !== "analysis" || pending.status !== "queued" || pending.result?.purpose !== "recording_finalization") {
      throw new SidecarError("conflict", "finalization_job_id is not a pending recording finalization job");
    }
    const client = runtime.client(args.instance_id);
    if (pending.instance_id !== client.descriptor.instanceId) throw new SidecarError("conflict", "finalization job belongs to another Minecraft instance");
    const { result } = await runtime.mutate("recording.finalize_and_open", args, signal);
    const updated = await runtime.jobs.registerBridgeJob(pending, client, objectResult(result));
    return ok("Recording finalization and replay opening started; follow the job to a terminal state.", { job: updated });
  }));

  server.registerTool("recording_add_marker", {
    title: "Add recording marker", description: "Add a named, categorized Replay Mod marker at the current recording tick.",
    inputSchema: z.object({ ...instance, request_id: requestId, name: z.string().min(1).max(256), category: z.enum(["action", "cut", "transition_out", "transition_in", "mistake", "note"]).default("note"), metadata: z.record(z.string(), z.unknown()).optional() }), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "recording.marker", args, signal, "Recording marker added.")));

  server.registerTool("replay_list", {
    title: "List replays", description: "List Replay Mod recordings available to the selected Minecraft instance.", inputSchema: z.object(instance), annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "replay.list", args, "Replay library listed.")));

  server.registerTool("replay_get", {
    title: "Get replay metadata", description: "Read Replay Mod metadata and compatibility information for a replay.",
    inputSchema: z.object({ ...instance, replay_id: z.string().optional(), path: z.string().optional() }).refine((value) => value.replay_id || value.path, "replay_id or path is required"), annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "replay.metadata", normalizeReplayPath(args), "Replay metadata read.")));

  server.registerTool("replay_open", {
    title: "Open replay", description: "Open a source replay through an immutable Replay Mod working copy.",
    inputSchema: z.object({ ...instance, request_id: requestId, replay_id: z.string().optional(), path: z.string().optional() }).refine((value) => value.replay_id || value.path, "replay_id or path is required"), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "replay.open", normalizeReplayPath(args), signal, "Replay opened from an immutable source.")));

  server.registerTool("replay_close", {
    title: "Close replay", description: "Close the current replay; unsaved timeline changes produce a typed conflict.",
    inputSchema: z.object({ ...instance, request_id: requestId }), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "replay.close", args, signal, "Replay closed.")));

  server.registerTool("replay_save", {
    title: "Save replay edit", description: "Save native Replay Mod timeline state to a new replay by default.",
    inputSchema: z.object({ ...instance, request_id: requestId, save_as: z.string().optional() }), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "replay.save", args, signal, "Replay edit saved.")));

  server.registerTool("replay_playback", {
    title: "Control replay playback", description: "Seek, play, pause, step, change speed, spectate, or detach using integer microseconds.",
    inputSchema: z.object({
      ...instance, request_id: requestId, operation: z.enum(["seek", "play", "pause", "speed", "step", "spectate", "detach"]),
      time_us: z.number().int().nonnegative().optional(), delta_us: z.number().int().optional(), speed: z.number().min(0).max(64).optional(),
      target_id: z.string().optional(), timeout_ms: timeout,
    }), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "replay.playback", args, signal, "Replay playback updated.")));

  server.registerTool("replay_observe", {
    title: "Observe the active replay", description: "Capture the active replay camera with synchronized replay state and verified image evidence.",
    inputSchema: z.object({ ...instance, view: z.enum(["player", "clean", "annotated"]).default("clean"), persist: z.boolean().default(true) }), annotations: GAME_READ,
  }, safe(async (args) => {
    const { result, client } = await runtime.read("observation.framebuffer", args);
    const data = objectResult(result);
    const [artifact] = await runtime.registerArtifacts(data, client, { provenance: { tool: "replay_observe" } }, args.persist);
    if (!artifact) throw new SidecarError("artifact_invalid", "replay observation returned no artifact");
    return okWith("Replay observation captured.", { ...data, artifact: artifactSummary(artifact, args.persist) }, [await runtime.artifacts.inlineImage(artifact)]);
  }));

  server.registerTool("replay_timeline_get", {
    title: "Get replay timeline", description: "Read native Replay Mod tracks and declared sidecar editorial tracks with capability truthfulness.",
    inputSchema: z.object({ ...instance, start_us: z.number().int().nonnegative().optional(), end_us: z.number().int().positive().optional() }), annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "timeline.get", args, "Replay timeline read.")));

  server.registerTool("replay_timeline_apply", {
    title: "Apply replay timeline operations", description: "Atomically apply revision-checked native timeline operations; unsupported editorial tracks remain sidecar-owned.",
    inputSchema: z.object({ ...instance, request_id: requestId, base_revision: z.string().min(1), operations: z.array(z.record(z.string(), z.unknown())).min(1).max(1024) }), annotations: MUTATE,
  }, safe(async (args, signal) => bridgeMutate(runtime, "timeline.apply", args, signal, "Timeline operations applied.")));

  server.registerTool("replay_preview", {
    title: "Preview replay edit", description: "Create a persistent low-resolution video job or sampled-frame/contact-sheet job before final rendering.",
    inputSchema: z.object({
      ...instance, request_id: requestId, project_id: z.string().optional(), shot_id: z.string().optional(), start_us: z.number().int().nonnegative().optional(),
      end_us: z.number().int().positive().optional(), start_frame: z.number().int().nonnegative().optional(), end_frame: z.number().int().positive().optional(),
      fps: z.number().positive().max(240).default(30), output_mode: z.enum(["video", "contact_sheet", "frames"]).default("contact_sheet"),
      frames: z.number().int().min(1).max(64).default(12), output: z.string().optional(), preset: z.string().optional(),
      width: z.number().int().min(16).max(7680).optional(), height: z.number().int().min(16).max(4320).optional(),
    }).superRefine(validateTimeOrFrameRange), annotations: MUTATE,
  }, safe(async (args, signal) => {
    const normalized = normalizeOutputRange(args);
    const client = runtime.client(args.instance_id);
    const job = await runtime.jobs.create("preview", { instance_id: client.descriptor.instanceId, ...(args.project_id ? { project_id: args.project_id } : {}) });
    if (args.output_mode === "video") {
      const renderArgs = { ...normalized, preset: args.preset ?? "draft_360p", output: args.output ?? `preview-${job.id}.mp4` };
      const { result } = await runtime.mutate("render.start", renderArgs, signal);
      const updated = await runtime.jobs.registerBridgeJob(job, client, objectResult(result));
      return ok("Low-resolution video preview started.", { job: updated });
    }
    await runtime.jobs.update(job.id, { status: "running" });
    const controller = runtime.jobs.createAbortController(job.id);
    void runSampledPreview(runtime, client.descriptor.instanceId, job.id, normalized, controller.signal, false).catch(async (error) => {
      if (runtime.jobs.get(job.id)?.status === "cancelled") return;
      await runtime.jobs.update(job.id, { status: "failed", failure: { code: errorCode(error), message: errorMessage(error) } });
    });
    return ok("Sampled preview started.", { job: runtime.jobs.get(job.id)! });
  }));

  server.registerTool("replay_validate_range", {
    title: "Validate replay shot range",
    description: "Evaluate the authored output timeline with settled seeks, chunk readiness, camera collision, subject distance, and line-of-sight checks.",
    inputSchema: z.object({
      ...instance, request_id: requestId, start_us: z.number().int().nonnegative().optional(), end_us: z.number().int().positive().optional(),
      start_frame: z.number().int().nonnegative().optional(), end_frame: z.number().int().positive().optional(), fps: z.number().positive().max(240).default(30),
      frames: z.number().int().min(2).max(64).default(8), subject_entity_id: z.number().int().optional(), chunk_radius: z.number().int().min(0).max(4).default(1),
      chunk_timeout_ms: z.number().int().min(0).max(30_000).default(5_000),
    }).superRefine(validateTimeOrFrameRange), annotations: MUTATE,
  }, safe(async (args) => {
    const normalized = normalizeOutputRange(args);
    const client = runtime.client(args.instance_id);
    const job = await runtime.jobs.create("analysis", { instance_id: client.descriptor.instanceId, result: { purpose: "replay_range_validation" } });
    await runtime.jobs.update(job.id, { status: "running" });
    const controller = runtime.jobs.createAbortController(job.id);
    void runSampledPreview(runtime, client.descriptor.instanceId, job.id, normalized, controller.signal, true).catch(async (error) => {
      if (runtime.jobs.get(job.id)?.status !== "cancelled") await runtime.jobs.update(job.id, { status: "failed", failure: { code: errorCode(error), message: errorMessage(error) } });
    });
    return ok("Replay range validation started.", { job: runtime.jobs.get(job.id)! });
  }));

  server.registerTool("render_presets", {
    title: "List render presets", description: "List built-in and local Replay Mod render presets and supported capabilities.", inputSchema: z.object(instance), annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "render.presets", args, "Render presets listed.")));

  server.registerTool("render_validate", {
    title: "Validate render", description: "Read-only preflight for replay paths, output, codec, FFmpeg, dimensions, frame count, and resources.", inputSchema: loose, annotations: READ,
  }, safe(async (args) => bridgeRead(runtime, "render.preflight", args, "Render preflight completed.")));

  server.registerTool("render_start", {
    title: "Start render", description: "Start a persistent Replay Mod video render job for a shot, range, or project batch.",
    inputSchema: z.object({ ...instance, request_id: requestId, project_id: z.string().optional(), shot_id: z.string().optional(), preset: z.string().optional(), output: z.string().min(1), width: z.number().int().positive().optional(), height: z.number().int().positive().optional(), fps: z.number().positive().optional(), start_us: z.number().int().nonnegative().optional(), end_us: z.number().int().positive().optional(), validation_job_id: z.uuid().optional() }).catchall(z.unknown()), annotations: MUTATE,
  }, safe(async (args, signal) => {
    await requireRenderValidation(runtime, args);
    return await startRenderJob(runtime, "render", "render.start", args, signal);
  }));

  server.registerTool("render_still", {
    title: "Render high-quality still", description: "Start a persistent exact-time still render job for framing or final review.",
    inputSchema: z.object({ ...instance, request_id: requestId, project_id: z.string().optional(), shot_id: z.string().optional(), time_us: z.number().int().nonnegative(), output: z.string().min(1), width: z.number().int().positive().optional(), height: z.number().int().positive().optional(), alpha: z.boolean().optional(), anti_aliasing: z.number().int().min(1).max(16).optional() }), annotations: MUTATE,
  }, safe(async (args, signal) => startRenderJob(runtime, "still", "render.still", args, signal)));

  server.registerTool("project_list", {
    title: "List production projects", description: "List sidecar-owned editor-neutral Replay MCP projects and validation state.", inputSchema: z.object({}), annotations: READ,
  }, safe(async () => {
    const projects = runtime.projects.list().map((project) => ({ id: project.id, title: project.title, revision: project.revision, updated_at: project.updated_at, validation: runtime.projects.validate(project) }));
    return ok(`${projects.length} project${projects.length === 1 ? "" : "s"}.`, { projects });
  }));

  server.registerTool("project_create", {
    title: "Create production project", description: "Create a revisioned editor-neutral project manifest.",
    inputSchema: z.object({ title: z.string().min(1), target_aspect_ratios: z.array(z.string()).optional(), frame_rate: z.number().positive().max(240).default(60), resolution: z.object({ width: z.number().int().positive(), height: z.number().int().positive() }).default({ width: 1920, height: 1080 }), output_root: z.string().min(1).default("renders"), creative_brief: z.string().optional() }), annotations: PROJECT_MUTATE,
  }, safe(async (args) => {
    const project = await runtime.projects.create(args);
    return ok(`Created project ${project.title}.`, { project, manifest_resource: `replay-mcp://project/${project.id}/manifest` });
  }));

  server.registerTool("project_get", {
    title: "Get production project", description: "Read a complete revisioned project manifest, including sidecar editorial tracks and artifact references.",
    inputSchema: z.object({ project_id: z.uuid() }), annotations: READ,
  }, safe(async ({ project_id }) => {
    const project = runtime.projects.get(project_id);
    return ok(`Project ${project.title}, revision ${project.revision}.`, { project, validation: runtime.projects.validate(project), manifest_resource: `replay-mcp://project/${project.id}/manifest` });
  }));

  server.registerTool("project_apply", {
    title: "Apply project operations", description: "Atomically apply revision-checked scene, take, shot, ordering, caption, narration, music, or intent changes.",
    inputSchema: z.object({ project_id: z.uuid(), base_revision: z.number().int().positive(), operations: z.array(z.record(z.string(), z.unknown())).min(1).max(1024) }), annotations: PROJECT_MUTATE,
  }, safe(async ({ project_id, base_revision, operations }) => {
    const project = await runtime.projects.apply(project_id, base_revision, operations);
    return ok(`Project advanced to revision ${project.revision}.`, { project, validation: runtime.projects.validate(project) });
  }));

  server.registerTool("project_validate", {
    title: "Validate production project", description: "Check references, ranges, renders, continuity, frame rates, and handoff completeness.",
    inputSchema: z.object({ project_id: z.uuid() }), annotations: READ,
  }, safe(async ({ project_id }) => {
    const project = runtime.projects.get(project_id);
    const validation = runtime.projects.validate(project);
    return ok(validation.valid ? "Project validation passed." : "Project validation failed.", { project_id, revision: project.revision, ...validation });
  }));

  server.registerTool("project_export_handoff", {
    title: "Export editor handoff", description: "Create a persistent versioned Replay MCP JSON handoff with absolute media paths and checksums.",
    inputSchema: z.object({ project_id: z.uuid() }), annotations: PROJECT_MUTATE,
  }, safe(async ({ project_id }) => {
    const project = runtime.projects.get(project_id);
    const job = await runtime.jobs.create("export", { project_id, status: "running", cancellable: false });
    try {
      const exported = await runtime.projects.exportHandoff(project);
      const artifact = await runtime.artifacts.registerLocal(exported.path, "application/json", { project_id, provenance: { project_revision: project.revision } });
      const updated = await runtime.jobs.update(job.id, { status: "completed", progress: 1, result_artifact_ids: [artifact.id], result: { sha256: exported.sha256 } });
      return ok("Project handoff exported.", { job: updated, artifact: withResourceUri(artifact), manifest: exported.manifest }, [resourceLink(artifact)]);
    } catch (error) {
      await runtime.jobs.update(job.id, { status: "failed", failure: { code: errorCode(error), message: errorMessage(error) } });
      throw error;
    }
  }));
}

function safe<A>(handler: (args: A, signal: AbortSignal) => Promise<ToolResult>): (args: A, ctx: { mcpReq: { signal: AbortSignal } }) => Promise<ToolResult> {
  return async (args, ctx) => {
    try { return await handler(args, ctx.mcpReq.signal); }
    catch (error) { return failure(error); }
  };
}

async function bridgeRead(runtime: ReplayMcpRuntime, method: string, args: Record<string, unknown>, message: string): Promise<ToolResult> {
  const { result, client } = await runtime.read(method, args);
  return ok(message, { instance_id: client.descriptor.instanceId, result });
}

async function bridgeMutate(runtime: ReplayMcpRuntime, method: string, args: Record<string, unknown>, signal: AbortSignal, message: string): Promise<ToolResult> {
  const { result, client } = await runtime.mutate(method, args, signal);
  const artifacts = await runtime.registerArtifacts(result, client);
  return ok(message, { instance_id: client.descriptor.instanceId, result, artifacts: artifacts.map(withResourceUri) }, artifacts.map(resourceLink));
}

async function performWithCapture(runtime: ReplayMcpRuntime, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolResult> {
  const client = runtime.client(typeof args.instance_id === "string" ? args.instance_id : undefined);
  const { instance_id: _, ...params } = args;
  const actions = Array.isArray(args.actions) ? args.actions : [];
  const captureMode = args.capture;
  const capturePromises: Promise<ArtifactRecord[]>[] = [];
  const capture = async (reason: string): Promise<ArtifactRecord[]> => {
    const frame = await client.call("observation.framebuffer", { view: "clean" }, { deadlineMs: 30_000, signal });
    return await runtime.registerArtifacts(frame, client, { provenance: { tool: "game_perform", reason } });
  };
  const progressListener = (event: Record<string, unknown>): void => {
    const step = typeof event.step === "number" ? event.step : -1;
    const action = actions[step];
    if ((captureMode === "checkpoints" || captureMode === "after_and_on_failure") && isObject(action) && action.kind === "checkpoint") {
      capturePromises.push(capture(`checkpoint:${step}`));
    }
  };
  client.on("action.progress", progressListener);
  try {
    const result = await runtime.leases.mutate(client, "action.start_batch", params, signal);
    if (captureMode === "after" || captureMode === "after_and_on_failure") capturePromises.push(capture("after"));
    const settled = await Promise.allSettled(capturePromises);
    const artifacts = settled.flatMap((item) => item.status === "fulfilled" ? item.value : []);
    const captureWarnings = settled.flatMap((item) => item.status === "rejected" ? [errorMessage(item.reason)] : []);
    const images = await Promise.all(artifacts.slice(0, 8).map(async (artifact) => await runtime.artifacts.inlineImage(artifact)));
    return okWith("Action batch completed.", {
      instance_id: client.descriptor.instanceId, result, observation_artifacts: artifacts.map(withResourceUri), capture_warnings: captureWarnings,
    }, [...images, ...artifacts.map(resourceLink)]);
  } catch (error) {
    await Promise.allSettled(capturePromises);
    if (captureMode === "after_and_on_failure") {
      try {
        const artifacts = await capture("failure");
        throw new SidecarError(errorCode(error), errorMessage(error), { failure_observation_artifacts: artifacts.map(withResourceUri) });
      } catch (captureError) {
        if (captureError instanceof SidecarError && captureError.code === errorCode(error)) throw captureError;
      }
    }
    throw error;
  } finally {
    client.off("action.progress", progressListener);
  }
}

async function startRenderJob(runtime: ReplayMcpRuntime, kind: "render" | "still", method: string, args: Record<string, unknown>, signal: AbortSignal): Promise<ToolResult> {
  const client = runtime.client(typeof args.instance_id === "string" ? args.instance_id : undefined);
  const job = await runtime.jobs.create(kind, { instance_id: client.descriptor.instanceId, ...(typeof args.project_id === "string" ? { project_id: args.project_id } : {}) });
  try {
    const { result } = await runtime.mutate(method, args, signal);
    const updated = await runtime.jobs.registerBridgeJob(job, client, objectResult(result));
    return ok(`${kind === "still" ? "Still" : "Render"} job started.`, { job: updated });
  } catch (error) {
    await runtime.jobs.update(job.id, { status: "failed", failure: { code: errorCode(error), message: errorMessage(error) } });
    throw error;
  }
}

async function runSampledPreview(runtime: ReplayMcpRuntime, instanceId: string, jobId: string, args: Record<string, unknown>, signal: AbortSignal, validationOnly: boolean): Promise<void> {
  const client = runtime.client(instanceId);
  const frameCount = typeof args.frames === "number" ? args.frames : 12;
  let startUs = typeof args.start_us === "number" ? args.start_us : undefined;
  let endUs = typeof args.end_us === "number" ? args.end_us : undefined;
  const timeline = objectResult(await client.call("timeline.get", {}));
  const timelineRevision = typeof timeline.revision === "string" ? timeline.revision : "unknown";
  if (startUs === undefined || endUs === undefined) {
    startUs = 0; endUs = timelineDurationUs(timeline);
    if (endUs <= 0) throw new SidecarError("invalid_request", "the authored timeline has no positive output duration");
  }
  const artifacts: ArtifactRecord[] = [];
  const samples: Record<string, unknown>[] = [];
  const labels: string[] = [];
  const metadata = {
    ...(typeof args.project_id === "string" ? { project_id: args.project_id } : {}),
    ...(typeof args.shot_id === "string" ? { shot_id: args.shot_id } : {}),
    provenance: { tool: "replay_preview" },
  };
  for (let index = 0; index < frameCount; index++) {
    if (signal.aborted) throw new SidecarError("cancelled", "preview sampling was cancelled");
    const fraction = frameCount === 1 ? 0 : index / (frameCount - 1);
    const timeUs = Math.round(startUs + (endUs - startUs) * fraction);
    const result = objectResult(await runtime.leases.mutate(client, "replay.preview_sample", {
      output_time_us: timeUs, view: "clean", subject_entity_id: args.subject_entity_id,
      chunk_radius: args.chunk_radius, chunk_timeout_ms: args.chunk_timeout_ms,
      request_id: `${String(args.request_id ?? jobId)}:sample:${index}`,
    }, signal));
    const registered = await runtime.registerArtifacts(result, client, { ...metadata, provenance: { ...metadata.provenance, output_time_us: timeUs, replay_time_us: result.replay_time_us } });
    artifacts.push(...registered);
    const validation = isObject(result.validation) ? result.validation : {};
    samples.push({ output_time_us: result.output_time_us ?? timeUs, replay_time_us: result.replay_time_us, validation });
    labels.push(`${formatTime(Number(result.output_time_us ?? timeUs))} out / ${formatTime(Number(result.replay_time_us ?? 0))} replay`);
    await runtime.jobs.update(jobId, { progress: (index + 1) / frameCount });
  }
  if (signal.aborted || runtime.jobs.get(jobId)?.status === "cancelled") return;
  const errors = samples.flatMap((sample) => isObject(sample.validation) && Array.isArray(sample.validation.errors) ? sample.validation.errors : []);
  const warnings = samples.flatMap((sample) => isObject(sample.validation) && Array.isArray(sample.validation.warnings) ? sample.validation.warnings : []);
  let output = artifacts;
  if (!validationOnly && args.output_mode === "contact_sheet" && artifacts.length) output = [await buildContactSheet(runtime, artifacts, `preview-${jobId}`, labels)];
  await runtime.jobs.update(jobId, {
    status: "completed", progress: 1, result_artifact_ids: output.map((item) => item.id), warnings: [...new Set(warnings.map(String))],
    result: { purpose: validationOnly ? "replay_range_validation" : "replay_preview", valid: errors.length === 0, errors, warnings, samples,
      start_us: startUs, end_us: endUs, timeline_revision: timelineRevision, sampled_frame_ids: artifacts.map((item) => item.id), output_mode: validationOnly ? "validation" : args.output_mode },
  });
}

async function buildContactSheet(runtime: ReplayMcpRuntime, frames: ArtifactRecord[], name: string, labels: string[] = []): Promise<ArtifactRecord> {
  const columns = Math.min(4, frames.length);
  const rows = Math.ceil(frames.length / columns);
  const cellWidth = 320, cellHeight = 180;
  const images = await Promise.all(frames.map(async (frame, index) => {
    const data = (await readFile(frame.path)).toString("base64");
    const x = (index % columns) * cellWidth, y = Math.floor(index / columns) * cellHeight;
    const label = escapeXml(labels[index] ?? String(index + 1));
    return `<image x="${x}" y="${y}" width="${cellWidth}" height="${cellHeight}" preserveAspectRatio="xMidYMid meet" href="data:${frame.mime_type};base64,${data}"/><text x="${x + 8}" y="${y + 18}" fill="white" stroke="black" paint-order="stroke">${label}</text>`;
  }));
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${columns * cellWidth}" height="${rows * cellHeight}" viewBox="0 0 ${columns * cellWidth} ${rows * cellHeight}"><rect width="100%" height="100%" fill="black"/>${images.join("")}</svg>`;
  await mkdir(runtime.artifacts.localRoot, { recursive: true });
  const path = join(runtime.artifacts.localRoot, `${name}.svg`);
  await writeFile(path, svg, "utf8");
  return await runtime.artifacts.registerLocal(path, "image/svg+xml", { provenance: { source_frame_ids: frames.map((item) => item.id) } });
}

function validateTimeOrFrameRange(value: Record<string, unknown>, context: z.RefinementCtx): void {
  const hasTime = value.start_us !== undefined || value.end_us !== undefined;
  const hasFrames = value.start_frame !== undefined || value.end_frame !== undefined;
  if (hasTime && hasFrames) context.addIssue({ code: "custom", message: "use either microseconds or frames, not both" });
  if (hasTime && (typeof value.start_us !== "number" || typeof value.end_us !== "number" || value.end_us <= value.start_us)) context.addIssue({ code: "custom", message: "start_us and end_us must form a positive range" });
  if (hasFrames && (typeof value.start_frame !== "number" || typeof value.end_frame !== "number" || value.end_frame <= value.start_frame)) context.addIssue({ code: "custom", message: "start_frame and end_frame must form a positive range" });
}

function normalizeOutputRange(args: Record<string, unknown>): Record<string, unknown> {
  const normalized = { ...args };
  if (typeof args.start_frame === "number" && typeof args.end_frame === "number") {
    const fps = typeof args.fps === "number" ? args.fps : 30;
    normalized.start_us = Math.round(args.start_frame * 1_000_000 / fps);
    normalized.end_us = Math.round(args.end_frame * 1_000_000 / fps);
  }
  delete normalized.start_frame; delete normalized.end_frame;
  return normalized;
}

function timelineDurationUs(timeline: Record<string, unknown>): number {
  let maximum = 0;
  if (!isObject(timeline.tracks)) return maximum;
  for (const frames of Object.values(timeline.tracks)) if (Array.isArray(frames)) for (const frame of frames) {
    if (isObject(frame) && typeof frame.time_us === "number") maximum = Math.max(maximum, frame.time_us);
  }
  return maximum;
}

async function requireRenderValidation(runtime: ReplayMcpRuntime, args: Record<string, unknown>): Promise<void> {
  const preset = args.preset === "preview_720p" ? "preview" : args.preset ?? "high_quality";
  if (preset === "preview" || preset === "draft_360p") return;
  if (typeof args.validation_job_id !== "string") throw new SidecarError("invalid_request", "final-quality renders require validation_job_id from replay_validate_range");
  const validation = runtime.jobs.get(args.validation_job_id);
  if (!validation || validation.status !== "completed" || validation.result?.purpose !== "replay_range_validation" || validation.result.valid !== true) {
    throw new SidecarError("conflict", "validation_job_id is not a successful replay range validation");
  }
  const { result } = await runtime.read("timeline.get", args);
  const timeline = objectResult(result);
  if (validation.result.timeline_revision !== timeline.revision) throw new SidecarError("conflict", "the replay timeline changed after range validation");
  const expectedStart = typeof args.start_us === "number" ? args.start_us : 0;
  const expectedEnd = typeof args.end_us === "number" ? args.end_us : timelineDurationUs(timeline);
  if (validation.result.start_us !== expectedStart || validation.result.end_us !== expectedEnd) {
    throw new SidecarError("conflict", "render range does not match the validated output range");
  }
}

function formatTime(timeUs: number): string { return `${(timeUs / 1_000_000).toFixed(2)}s`; }
function escapeXml(value: string): string { return value.replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&apos;" }[character]!)); }

function ok(message: string, structuredContent: Record<string, unknown>, extra: ToolContent[] = []): ToolResult {
  return { content: [{ type: "text", text: message }, ...extra], structuredContent };
}
function okWith(message: string, structuredContent: Record<string, unknown>, extra: ToolContent[]): ToolResult { return ok(message, structuredContent, extra); }

function failure(error: unknown): ToolResult {
  const code = errorCode(error), message = errorMessage(error);
  const data = error instanceof BridgeRpcError || error instanceof SidecarError ? error.data : {};
  return { isError: true, content: [{ type: "text", text: `${code}: ${message}` }], structuredContent: { ok: false, error: { code, message, data } } };
}

function errorCode(error: unknown): string {
  if (error instanceof BridgeRpcError || error instanceof SidecarError) return error.code;
  if (error instanceof z.ZodError) return "invalid_response";
  return "internal_error";
}
function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function isObject(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value); }
function normalizeReplayPath<T extends Record<string, unknown>>(args: T): T { return { ...args, path: args.path ?? args.replay_id }; }
function withResourceUri(artifact: ArtifactRecord): Record<string, unknown> { return { ...artifact, resource_uri: `replay-mcp://artifact/${artifact.id}` }; }
function artifactSummary(artifact: ArtifactRecord, persisted: boolean): Record<string, unknown> { return persisted ? withResourceUri(artifact) : { ...artifact, persisted: false }; }
function resourceLink(artifact: ArtifactRecord): ToolContent { return { type: "resource_link", uri: `replay-mcp://artifact/${artifact.id}`, name: artifact.filename, mimeType: artifact.mime_type, description: `Verified SHA-256 ${artifact.sha256}` }; }

function projectGameQuery(snapshot: Record<string, unknown>, args: Record<string, unknown>): unknown {
  switch (args.kind) {
    case "player": return snapshot.player ?? null;
    case "world": return snapshot.world ?? null;
    case "inventory": return snapshot.inventory ?? [];
    case "scoreboard": return snapshot.scoreboard ?? [];
    case "chat_or_system_messages": return limited(snapshot.chat_or_system_messages, args.limit);
    case "target": return snapshot.crosshair_target ?? null;
    case "screen": return { screen: snapshot.screen ?? "none", widgets: limited(snapshot.widgets, args.limit) };
    case "entities": return filterObserved(snapshot.nearby_entities, args);
    case "blocks": return filterObserved(snapshot.loaded_nearby_blocks, args);
    default: return snapshot;
  }
}

function filterObserved(value: unknown, args: Record<string, unknown>): unknown[] {
  if (!Array.isArray(value)) return [];
  const ids = Array.isArray(args.observation_ids) ? new Set(args.observation_ids.filter((item): item is string => typeof item === "string")) : undefined;
  const bounds = isObject(args.bounds) ? args.bounds : undefined;
  const filtered = value.filter((item) => {
    if (!isObject(item)) return false;
    if (ids && !ids.has(String(item.observation_id))) return false;
    if (typeof args.distance === "number" && typeof item.distance === "number" && item.distance > args.distance) return false;
    if (typeof args.type === "string" && String(item.type ?? item.state ?? "") !== args.type) return false;
    if (typeof args.name === "string" && !String(item.name ?? "").toLowerCase().includes(args.name.toLowerCase())) return false;
    if (bounds && !insideBounds(item, bounds)) return false;
    return true;
  });
  return filtered.slice(0, typeof args.limit === "number" ? args.limit : filtered.length);
}

function insideBounds(item: Record<string, unknown>, bounds: Record<string, unknown>): boolean {
  for (const axis of ["x", "y", "z"] as const) {
    const coordinate = item[axis];
    if (typeof coordinate !== "number") continue;
    const minimum = bounds[`min_${axis}`], maximum = bounds[`max_${axis}`];
    if (typeof minimum === "number" && coordinate < minimum) return false;
    if (typeof maximum === "number" && coordinate > maximum) return false;
  }
  return true;
}

function limited(value: unknown, limit: unknown): unknown[] {
  if (!Array.isArray(value)) return [];
  return value.slice(0, typeof limit === "number" ? limit : value.length);
}
