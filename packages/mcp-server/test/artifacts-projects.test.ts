import { createHash } from "node:crypto";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { describe, expect, it } from "vitest";
import { ArtifactStore } from "../src/artifacts/store.js";
import { ProjectStore } from "../src/projects/store.js";
import { JobStore } from "../src/jobs/store.js";
import { AuditLog } from "../src/persistence.js";
import type { CapturedTake } from "../src/capture/importer.js";

describe("artifacts and projects", () => {
  it("confines paths and verifies sizes and checksums", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-artifacts-"));
    const allowed = join(root, "allowed");
    await mkdir(allowed);
    const path = join(allowed, "frame.bin");
    const bytes = Buffer.from("verified");
    await writeFile(path, bytes);
    const sha256 = createHash("sha256").update(bytes).digest("hex");
    const store = new ArtifactStore(join(root, "data"));
    await store.load();
    const record = await store.register({ path, mime_type: "application/octet-stream", size: bytes.length, sha256, complete: true }, "instance", [allowed]);
    expect(record.sha256).toBe(sha256);
    await expect(store.register({ path, mime_type: "application/octet-stream", size: bytes.length, sha256: "0".repeat(64), complete: true }, "instance", [allowed])).rejects.toMatchObject({ code: "artifact_checksum_failed" });
    const outside = join(root, "outside.bin");
    await writeFile(outside, bytes);
    await expect(store.register({ path: outside, mime_type: "application/octet-stream", size: bytes.length, sha256, complete: true }, "instance", [allowed])).rejects.toMatchObject({ code: "artifact_path_denied" });
  });

  it("enforces optimistic project revisions and exports checksummed handoffs", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-projects-"));
    const artifacts = new ArtifactStore(root);
    await artifacts.load();
    const projects = new ProjectStore(root, artifacts);
    await projects.load();
    const project = await projects.create({ title: "Film", frame_rate: 30 });
    const updated = await projects.apply(project.id, 1, [{ op: "upsert_scene", scene: { id: "scene-1", shots: [{ id: "shot-1", in_us: 0, out_us: 1_000_000 }] } }]);
    expect(updated.revision).toBe(2);
    const nested = await projects.apply(project.id, 2, [
      { op: "upsert_take", scene_id: "scene-1", take: { id: "take-1", replay_id: "replay-1", shots: [] } },
      { op: "upsert_shot", scene_id: "scene-1", take_id: "take-1", shot: { id: "shot-2", in_us: 0, out_us: 500_000 } },
      { op: "set_note", key: "narration", value: "Open on the wide shot." },
    ]);
    expect(nested.revision).toBe(3);
    expect(((nested.scenes[0]!.takes as Record<string, unknown>[])[0]!.shots as Record<string, unknown>[])[0]!.id).toBe("shot-2");
    await expect(projects.apply(project.id, 1, [{ op: "set", field: "title", value: "stale" }])).rejects.toMatchObject({ code: "revision_conflict" });
    expect(projects.validate(nested).valid).toBe(true);
    const handoff = await projects.exportHandoff(nested);
    expect(handoff.manifest).toMatchObject({ format: "replay-mcp.handoff/1", project_revision: 3 });
    expect(handoff.sha256).toHaveLength(64);
  });

  it("atomically and idempotently imports captured takes", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-captured-takes-"));
    const artifacts = new ArtifactStore(root); await artifacts.load();
    const projects = new ProjectStore(root, artifacts); await projects.load();
    const project = await projects.create({ title: "Player Film" });
    const withScene = await projects.apply(project.id, 1, [{ op: "upsert_scene", scene: { id: "scene-player", takes: [] } }]);
    const captured: CapturedTake = {
      id: "take-player", take_id: "take-player", project_id: project.id, scene_id: "scene-player",
      format: "replay-mcp.captured-take/1", provenance: "player",
      replay_id: "a".repeat(64), replay_path: "/replays/player.mcpr", replay_sha256: "a".repeat(64), replay_size: 100,
      duration_us: 1_000_000, compatibility: { minecraft_version: "26.2" },
      accepted_clips: [{ clip_id: "11111111-1111-4111-8111-111111111111", replay_in_us: 100, replay_out_us: 200 }],
      diagnostics: [{ clip_id: "22222222-2222-4222-8222-222222222222", status: "revoked", code: "clip_revoked", message: "revoked", markers: [] }],
      shots: [{ id: "shot-player", replay_in_us: 100, replay_out_us: 200, in_us: 100, out_us: 200 }],
    };
    const imported = await projects.importCapturedTake(project.id, withScene.revision, "scene-player", captured);
    expect(imported.changed).toBe(true);
    expect(imported.project.revision).toBe(3);
    const repeated = await projects.importCapturedTake(project.id, imported.project.revision, "scene-player", captured);
    expect(repeated.changed).toBe(false);
    expect(repeated.project.revision).toBe(3);
    const takes = repeated.project.scenes[0]!.takes as Record<string, unknown>[];
    expect(takes).toHaveLength(1);
    expect((takes[0]!.shots as unknown[])).toHaveLength(1);
    await expect(projects.importCapturedTake(project.id, 2, "scene-player", captured)).rejects.toMatchObject({ code: "revision_conflict" });
    await expect(projects.importCapturedTake(project.id, 3, "missing", captured)).rejects.toMatchObject({ code: "invalid_request" });
  });

  it("limits sidecar cancellation to the owning session and recovers interrupted jobs", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-jobs-"));
    const artifacts = new ArtifactStore(root); await artifacts.load();
    const first = new JobStore(root, artifacts, new AuditLog(root), "session-1"); await first.load();
    const job = await first.create("preview", { status: "running" });
    const controller = first.createAbortController(job.id);
    expect((await first.cancelSidecar(job)).status).toBe("cancelled");
    expect(controller.signal.aborted).toBe(true);
    const running = await first.create("preview", { status: "running" });
    const second = new JobStore(root, artifacts, new AuditLog(root), "session-2"); await second.load();
    expect(second.get(running.id)).toMatchObject({ status: "failed", failure: { code: "sidecar_restarted" } });
    await expect(second.cancelSidecar(job)).rejects.toMatchObject({ code: "job_control_required" });
  });
});
