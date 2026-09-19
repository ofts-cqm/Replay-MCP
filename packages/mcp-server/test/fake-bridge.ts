import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { WebSocketServer, type WebSocket } from "ws";
import type { IncomingMessage } from "node:http";

export interface FakeBridge {
  instanceId: string;
  gameDir: string;
  port: number;
  calls: { method: string; params: Record<string, unknown> }[];
  close(): Promise<void>;
}

export async function createFakeBridge(gameDir: string): Promise<FakeBridge> {
  const instanceId = crypto.randomUUID();
  const token = "ab".repeat(32);
  const artifacts = join(gameDir, ".replay-mcp", "artifacts");
  await mkdir(artifacts, { recursive: true });
  const pngPath = join(artifacts, "frame.png");
  const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=", "base64");
  await writeFile(pngPath, png);
  const clipPath = join(artifacts, "clip.mp4");
  const clip = Buffer.from("fake-mp4-contents");
  await writeFile(clipPath, clip);
  const replayPath = join(artifacts, "take.mcpr");
  const replay = Buffer.from("fake-replay-contents");
  await writeFile(replayPath, replay);
  const artifact = (path: string, data: Buffer, mime_type: string) => ({
    path, mime_type, size: data.length, sha256: createHash("sha256").update(data).digest("hex"), complete: true,
    ...(mime_type === "image/png" ? { width: 1, height: 1 } : {}),
  });

  const calls: { method: string; params: Record<string, unknown> }[] = [];
  let leaseOwner: WebSocket | undefined;
  let leaseId = "";
  let fence = 0;
  let recording = false;
  let revision = "1";
  const wss = new WebSocketServer({
    host: "127.0.0.1", port: 0, path: "/bridge",
    verifyClient: ({ req }: { origin: string; secure: boolean; req: IncomingMessage }) => req.headers["x-replay-mcp-token"] === token,
  });
  await new Promise<void>((resolve) => wss.once("listening", resolve));
  const address = wss.address();
  if (!address || typeof address === "string") throw new Error("fake bridge did not bind TCP");

  const readOnly = new Set(["system.hello", "system.status", "lease.status", "observation.framebuffer", "observation.motion_burst", "observation.query", "recording.status", "replay.list", "replay.metadata", "timeline.get", "render.presets", "render.preflight", "test.timeout"]);
  wss.on("connection", (socket) => {
    let hello = false;
    socket.on("close", () => { if (leaseOwner === socket) { leaseOwner = undefined; leaseId = ""; fence++; } });
    socket.on("message", (raw) => {
      const request = JSON.parse(raw.toString()) as { id: string; method: string; params?: Record<string, unknown> };
      const params = request.params ?? {};
      calls.push({ method: request.method, params });
      const success = (result: unknown) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: request.id, result }));
      const failure = (code: string, message: string) => socket.send(JSON.stringify({ jsonrpc: "2.0", id: request.id, error: { code, message, data: {} } }));
      if (!hello && request.method !== "system.hello") return failure("protocol_mismatch", "hello required");
      if (!readOnly.has(request.method) && !["lease.acquire", "lease.heartbeat", "lease.release", "system.cancel"].includes(request.method)) {
        if (leaseOwner !== socket || params.lease_id !== leaseId) return failure("control_required", "lease required");
        if (params.fence !== fence) return failure("stale_fence", "fence stale");
      }
      switch (request.method) {
        case "system.hello":
          if (params.protocol !== "replay-mcp.bridge/1" || params.instance_id !== instanceId) return failure("protocol_mismatch", "identity mismatch");
          hello = true;
          success(status({ protocol: "replay-mcp.bridge/1", instance_id: instanceId, process_id: process.pid, connection_id: crypto.randomUUID() }));
          break;
        case "system.status": success(status()); break;
        case "lease.status": success(leaseOwner ? { held: true, epoch: fence, owner_label: "test", expires_at: new Date(Date.now() + 15_000).toISOString() } : { held: false, epoch: fence }); break;
        case "lease.acquire":
          if (leaseOwner) return failure("control_busy", "already held");
          leaseOwner = socket; leaseId = `lease-${crypto.randomUUID()}-01234567890123456789012345678901`; fence++;
          success(lease()); break;
        case "lease.heartbeat":
          if (leaseOwner !== socket || params.lease_id !== leaseId) return failure("control_required", "lost");
          success(lease()); break;
        case "lease.release": leaseOwner = undefined; leaseId = ""; fence++; success({ released: true }); break;
        case "observation.framebuffer": success({ ...artifact(pngPath, png, "image/png"), view: params.view ?? "player", capture_tick: 80, snapshot: { synchronized: true, tick: 80, player: { x: 1, y: 64, z: 2 } } }); break;
        case "observation.motion_burst": success({ requested_frames: params.frames ?? 2, dropped_frames: 0, view: params.view ?? "clean", frames: [artifact(pngPath, png, "image/png")] }); break;
        case "observation.query": success({ kind: params.kind, tick: 80, player: { x: 1, y: 64, z: 2 } }); break;
        case "action.start_batch": {
          const actions = Array.isArray(params.actions) ? params.actions as Record<string, unknown>[] : [];
          success({ trace: actions.map((action) => ({ kind: action.kind, status: "completed", ...(action.kind === "navigate_to" ? { navigation: { native_node_count: 4, replans: 0, reached: true } } : {}) })), final_state: { tick: 81 } });
          break;
        }
        case "recording.status": success({ armed: true, logical_recording: recording, duration_us: 1_000_000 }); break;
        case "recording.start": recording = true; success({ armed: true, logical_recording: true, take_id: params.take_id ?? "take-1" }); break;
        case "recording.marker": success({ success: true }); break;
        case "recording.stop":
          recording = false; success({ logical_recording: false, status: "pending_finalization", recoverable: true, take_id: "take-1" });
          break;
        case "recording.finalize_and_open": {
          const jobId = crypto.randomUUID();
          success({ job_id: jobId, status: "running", phase: "finalizing", take_id: "take-1" });
          setTimeout(() => {
            if (socket.readyState === socket.OPEN) socket.send(JSON.stringify({ jsonrpc: "2.0", method: "recording.finalization", params: { job_id: jobId, status: "completed", phase: "completed", progress: 1, take_id: "take-1", artifact: artifact(replayPath, replay, "application/x-minecraft-replay"), replay: { source: replayPath, working_copy: "working.mcpr", source_immutable: true } } }));
          }, 25);
          break;
        }
        case "replay.list": success([{ name: "take.mcpr", path: "take.mcpr", size: 100 }]); break;
        case "replay.metadata": success({ path: params.path, duration_us: 5_000_000, minecraft_version: "26.2" }); break;
        case "replay.open": success({ source: params.path, working_copy: "working.mcpr", source_immutable: true }); break;
        case "replay.close": success({ success: true }); break;
        case "replay.save": success({ success: true, path: "take-edit-1.mcpr" }); break;
        case "replay.playback": success({ success: true, time_us: params.time_us ?? 0, speed: params.speed ?? 0 }); break;
        case "replay.preview_sample": success({ ...artifact(pngPath, png, "image/png"), view: "clean", output_time_us: params.output_time_us, replay_time_us: Number(params.output_time_us) + 1_000_000, validation: { valid: true, chunks_ready: true, camera_inside_block: false, warnings: [], errors: [] } }); break;
        case "timeline.get": success({ revision, tracks: { replay_time: [{ time_us: 0, replay_time_us: 1_000_000 }, { time_us: 5_000_000, replay_time_us: 6_000_000 }], camera_position: [{ time_us: 0 }, { time_us: 5_000_000 }] }, native_tracks: ["replay_time", "camera_position"], sidecar_tracks: ["shots", "excluded_ranges", "fov", "look_at"] }); break;
        case "timeline.apply": revision = String(Number(revision) + 1); success({ revision, tracks: {}, warnings: [], undo_token: crypto.randomUUID() }); break;
        case "render.presets": success([{ id: "preview_720p", width: 1280, height: 720, fps: 30 }]); break;
        case "render.preflight": success({ valid: true, errors: [], warnings: [], estimated_frames: 30 }); break;
        case "render.start":
        case "render.still": {
          const jobId = crypto.randomUUID();
          success({ job_id: jobId, status: "running", output: params.output });
          setTimeout(() => {
            if (socket.readyState !== socket.OPEN) return;
            socket.send(JSON.stringify({ jsonrpc: "2.0", method: "render.progress", params: { job_id: jobId, progress: 0.5 } }));
            socket.send(JSON.stringify({ jsonrpc: "2.0", method: "render.completed", params: { job_id: jobId, progress: 1, complete: true, artifact: request.method === "render.still" ? artifact(pngPath, png, "image/png") : artifact(clipPath, clip, "video/mp4") } }));
          }, 25);
          break;
        }
        case "render.cancel": success({ cancelled: true, job_id: params.job_id }); break;
        case "system.cancel": success({ cancelled: true }); break;
        case "test.timeout": break;
        default: failure("invalid_request", `unknown ${request.method}`);
      }
    });
  });

  function lease() { return { lease_id: leaseId, fence, owner_label: "test", expires_at: new Date(Date.now() + 15_000).toISOString() }; }
  function status(extra: Record<string, unknown> = {}) {
    return {
      minecraft_version: "26.2", replay_mod_version: "26.2-2.6.27", connected: true, runtime_mode: "live_idle",
      capabilities: { structured_observation: true, framebuffer_capture: true, native_timeline: true, native_fov: false, native_look_at: false, navigation: { ground: true, engine: "minecraft_walk_node_evaluator", loaded_chunks_only: true, max_distance: 128, unsupported_travel_modes: ["swimming", "flight", "vehicles"] } },
      lease: leaseOwner ? { held: true, epoch: fence, owner_label: "test" } : { held: false, epoch: fence },
      lease_policy: { ttl_ms: 15_000, heartbeat_interval_ms: 1_000, idle_ceiling_ms: 300_000 },
      command_policy: { enabled: false, locally_managed: true },
      flight_policy: { automation_enabled: true, requires_granted_ability: true, currently_granted: true },
      allowed_artifact_roots: [artifacts], time_precision_us: 1_000, ...extra,
    };
  }

  const instanceDir = join(gameDir, ".replay-mcp", "instances", instanceId);
  await mkdir(instanceDir, { recursive: true });
  await writeFile(join(instanceDir, "token"), token, { mode: 0o600 });
  await writeFile(join(instanceDir, "bridge.json"), JSON.stringify({
    instanceId, processId: process.pid, port: address.port, createdAt: new Date().toISOString(), displayName: "Fake Minecraft 26.2",
    gameDirectory: gameDir, protocolVersion: "replay-mcp.bridge/1", modVersion: "0.1.0", minecraftVersion: "26.2", replayModVersion: "26.2-2.6.27",
  }));

  return {
    instanceId, gameDir, port: address.port, calls,
    async close() { for (const client of wss.clients) client.terminate(); await new Promise<void>((resolve) => wss.close(() => resolve())); },
  };
}
