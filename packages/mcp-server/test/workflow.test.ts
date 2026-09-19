import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/client";
import { InMemoryTransport } from "@modelcontextprotocol/server";
import { afterEach, describe, expect, it } from "vitest";
import { buildServer, type BuiltReplayMcpServer } from "../src/server.js";
import { createFakeBridge, type FakeBridge } from "./fake-bridge.js";

describe("in-process MCP filmmaking workflow", () => {
  let built: BuiltReplayMcpServer | undefined;
  let fake: FakeBridge | undefined;
  let client: Client | undefined;
  afterEach(async () => {
    await client?.close().catch(() => undefined);
    await built?.close().catch(() => undefined);
    await fake?.close().catch(() => undefined);
  });

  it("covers offline status and the full fake-bridge acceptance flow", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-workflow-"));
    const gameDir = join(root, "game");
    built = await buildServer({ dataDir: join(root, "offline-data"), gameDirs: [gameDir], guessedGameDirs: [], discoveryIntervalMs: 60_000, command: "serve" });
    let pair = InMemoryTransport.createLinkedPair();
    client = new Client({ name: "test-client", version: "1.0.0" });
    await built.server.connect(pair[1]);
    await client.connect(pair[0]);
    expect(structured(await client.callTool({ name: "system_status", arguments: {} })).state).toBe("offline");
    await client.close();
    await built.close();

    fake = await createFakeBridge(gameDir);
    built = await buildServer({ dataDir: join(root, "data"), gameDirs: [gameDir], guessedGameDirs: [], discoveryIntervalMs: 60_000, command: "serve" });
    pair = InMemoryTransport.createLinkedPair();
    client = new Client({ name: "test-client", version: "1.0.0" });
    await built.server.connect(pair[1]);
    await client.connect(pair[0]);

    const tools = await client.listTools();
    expect(tools.tools).toHaveLength(39);
    expect(new Set(tools.tools.map((tool) => tool.name)).size).toBe(39);

    const online = structured(await call(client, "system_status", {}));
    expect(online.state).toBe("online");
    const [onlineInstance] = online.instances as Record<string, unknown>[];
    expect(onlineInstance).toBeDefined();
    expect(((onlineInstance!.capabilities as Record<string, unknown>).navigation as Record<string, unknown>)).toMatchObject({ ground: true, engine: "minecraft_walk_node_evaluator", max_distance: 128 });
    const invalidNavigation = await client.callTool({ name: "game_perform", arguments: { actions: [{ kind: "navigate_to", x: 1, y: 64 }] } });
    expect(invalidNavigation.isError).toBe(true);

    const playerProject = structured(await call(client, "project_create", { title: "Player Capture", frame_rate: 30, resolution: { width: 1920, height: 1080 }, output_root: "renders" })).project as Record<string, unknown>;
    const playerSceneProject = structured(await call(client, "project_apply", {
      project_id: playerProject.id, base_revision: playerProject.revision,
      operations: [{ op: "upsert_scene", scene: { id: "player-scene", takes: [] } }],
    })).project as Record<string, unknown>;
    const replayList = (structured(await call(client, "replay_list", {})).result as Record<string, unknown>[]);
    const importedPlayer = structured(await call(client, "project_import_replay", {
      project_id: playerProject.id, base_revision: playerSceneProject.revision, scene_id: "player-scene", path: replayList[0]!.path,
    }));
    expect(importedPlayer).toMatchObject({ changed: true, accepted_count: 1, diagnostic_count: 2 });
    const capturedTake = importedPlayer.captured_take as Record<string, unknown>;
    expect(capturedTake).toMatchObject({ format: "replay-mcp.captured-take/1", provenance: "player" });
    expect(capturedTake.accepted_clips).toHaveLength(1);
    expect(capturedTake.shots).toHaveLength(1);
    const importedRevision = (importedPlayer.project as Record<string, unknown>).revision;
    expect(structured(await call(client, "project_import_replay", {
      project_id: playerProject.id, base_revision: importedRevision, scene_id: "player-scene", path: replayList[0]!.path,
    }))).toMatchObject({ changed: false, accepted_count: 1 });
    expect(fake.calls.some((entry) => entry.method === "lease.acquire")).toBe(false);

    expect(structured(await call(client, "control_acquire", {})).lease_id).toBeTypeOf("string");
    const observation = await call(client, "game_observe", { view: "annotated" });
    expect(observation.content.some((block) => block.type === "image")).toBe(true);
    expect(structured(observation).capture_tick).toBe(80);
    const persistedBefore = (structured(await call(client, "artifact_list", {})).artifacts as unknown[]).length;
    const ephemeral = structured(await call(client, "game_observe", { view: "clean", persist: false })).artifact as Record<string, unknown>;
    expect(ephemeral.persisted).toBe(false);
    expect((structured(await call(client, "artifact_list", {})).artifacts as unknown[]).length).toBe(persistedBefore);
    expect(structured(await call(client, "game_query", { kind: "player" })).result).toMatchObject({ x: 1, y: 64, z: 2 });
    const navigated = structured(await call(client, "game_perform", { actions: [{ kind: "navigate_to", x: 8.5, y: 64, z: 2.5, tolerance: 1, sprint: false }] }));
    expect(((navigated.result as Record<string, unknown>).trace as Record<string, unknown>[])[0]).toMatchObject({ kind: "navigate_to", navigation: { reached: true } });
    const performed = await call(client, "game_perform", { actions: [{ kind: "turn", yaw: 10, pitch: 0 }], capture: "after" });
    expect((structured(performed).result as Record<string, unknown>).trace).toBeDefined();
    expect(performed.content.some((block) => block.type === "image")).toBe(true);
    await call(client, "recording_start", { take_id: "take-1" });
    await call(client, "recording_add_marker", { name: "action", category: "action" });
    const stopped = structured(await call(client, "recording_stop", {}));
    expect((stopped.result as Record<string, unknown>).status).toBe("pending_finalization");
    const finalizationJob = stopped.finalization_job as Record<string, unknown>;
    await call(client, "recording_finalize_and_open", { finalization_job_id: finalizationJob.id });
    await waitFor(async () => (structured(await call(client!, "job_get", { job_id: finalizationJob.id })).job as Record<string, unknown>).status === "completed");
    const timeline = structured(await call(client, "replay_timeline_get", {})).result as Record<string, unknown>;
    await call(client, "replay_timeline_apply", { base_revision: timeline.revision, operations: [{ op: "upsert", track: "camera_position", time_us: 0, value: { x: 0, y: 64, z: 0 } }] });

    const preview = structured(await call(client, "replay_preview", { output_mode: "contact_sheet", frames: 2, start_us: 0, end_us: 1_000_000 }));
    const previewJob = preview.job as Record<string, unknown>;
    await waitFor(async () => (structured(await call(client!, "job_get", { job_id: previewJob.id })).job as Record<string, unknown>).status === "completed");

    const validation = structured(await call(client, "replay_validate_range", { start_us: 0, end_us: 1_000_000, frames: 2 }));
    const validationJob = validation.job as Record<string, unknown>;
    await waitFor(async () => (structured(await call(client!, "job_get", { job_id: validationJob.id })).job as Record<string, unknown>).status === "completed");

    expect((structured(await call(client, "render_validate", { output: "clip.mp4" })).result as Record<string, unknown>).valid).toBe(true);
    const render = structured(await call(client, "render_start", { output: "clip.mp4", preset: "high_quality", start_us: 0, end_us: 1_000_000, validation_job_id: validationJob.id }));
    const renderJob = render.job as Record<string, unknown>;
    await waitFor(async () => (structured(await call(client!, "job_get", { job_id: renderJob.id })).job as Record<string, unknown>).status === "completed");
    const artifacts = structured(await call(client, "artifact_list", {})).artifacts as Record<string, unknown>[];
    const video = artifacts.find((artifact) => artifact.mime_type === "video/mp4");
    expect(video).toBeDefined();
    const resource = await client.readResource({ uri: String(video!.resource_uri) });
    expect(resource.contents[0]).toMatchObject({ mimeType: "video/mp4" });

    const created = structured(await call(client, "project_create", { title: "Fake Film", frame_rate: 30, resolution: { width: 1920, height: 1080 }, output_root: "renders" })).project as Record<string, unknown>;
    const applied = structured(await call(client, "project_apply", { project_id: created.id, base_revision: created.revision, operations: [{ op: "upsert_scene", scene: { id: "scene-1", shots: [{ id: "shot-1", in_us: 0, out_us: 1_000_000, render_artifact_id: video!.id }] } }] })).project as Record<string, unknown>;
    expect(applied.revision).toBe(2);
    expect(structured(await call(client, "project_validate", { project_id: created.id })).valid).toBe(true);
    const handoff = structured(await call(client, "project_export_handoff", { project_id: created.id }));
    expect((handoff.artifact as Record<string, unknown>).resource_uri).toMatch(/^replay-mcp:\/\/artifact\//);
    expect(structured(await call(client, "control_release", {})).released).toBe(true);
  }, 20_000);

  it("hot-reloads a persisted game directory without restarting the sidecar", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-hot-discovery-"));
    const dataDir = join(root, "data");
    const configPath = join(dataDir, "config.json");
    await mkdir(dataDir, { recursive: true });
    await writeFile(configPath, JSON.stringify({ storage_version: 1, game_dirs: [] }));
    built = await buildServer({ dataDir, gameDirs: [join(root, "missing")], guessedGameDirs: [], configFiles: [configPath], discoveryIntervalMs: 60_000, command: "serve" });
    fake = await createFakeBridge(join(root, "hot-game"));
    await writeFile(configPath, JSON.stringify({ storage_version: 1, game_dirs: [fake.gameDir] }));
    await built.runtime.discovery.discoverOnce();
    expect(built.runtime.client().descriptor.instanceId).toBe(fake.instanceId);
  });
});

async function call(client: Client, name: string, args: Record<string, unknown>) {
  const result = await client.callTool({ name, arguments: args });
  expect(result.isError, JSON.stringify(result.structuredContent)).not.toBe(true);
  return result;
}

function structured(result: { structuredContent?: unknown }): Record<string, unknown> {
  if (!result.structuredContent || typeof result.structuredContent !== "object" || Array.isArray(result.structuredContent)) throw new Error("missing structured content");
  return result.structuredContent as Record<string, unknown>;
}

async function waitFor(predicate: () => Promise<boolean>, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("condition timed out");
}
