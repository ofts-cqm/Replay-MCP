import { join } from "node:path";
import { createHash } from "node:crypto";
import { JsonCollection, writeJsonAtomic } from "../persistence.js";
import { SidecarError } from "../bridge/discovery.js";
import type { ArtifactStore } from "../artifacts/store.js";

export interface ProjectManifest {
  id: string;
  storage_version: 1;
  format: "replay-mcp.project/1";
  revision: number;
  title: string;
  created_at: string;
  updated_at: string;
  frame_rate: number;
  resolution: { width: number; height: number };
  target_aspect_ratios: string[];
  output_root: string;
  creative_brief?: string;
  scenes: Record<string, unknown>[];
  notes: Record<string, unknown>;
}

export class ProjectStore {
  readonly #projects: JsonCollection<ProjectManifest>;
  readonly #artifacts: ArtifactStore;
  constructor(dataDir: string, artifacts: ArtifactStore) {
    this.#artifacts = artifacts;
    this.#projects = new JsonCollection(dataDir, "projects/index.json");
  }
  async load(): Promise<void> { await this.#projects.load(); }
  list(): ProjectManifest[] { return this.#projects.values(); }
  get(id: string): ProjectManifest {
    const project = this.#projects.get(id);
    if (!project) throw new SidecarError("project_not_found", `project ${id} was not found`);
    return project;
  }

  async create(input: Record<string, unknown>): Promise<ProjectManifest> {
    const now = new Date().toISOString();
    const resolution = isObject(input.resolution) ? input.resolution : {};
    const project: ProjectManifest = {
      id: crypto.randomUUID(), storage_version: 1, format: "replay-mcp.project/1",
      revision: 1, title: stringValue(input.title, "Untitled Replay Project"),
      created_at: now, updated_at: now,
      frame_rate: numberValue(input.frame_rate, 60),
      resolution: { width: numberValue(resolution.width, 1920), height: numberValue(resolution.height, 1080) },
      target_aspect_ratios: Array.isArray(input.target_aspect_ratios) ? input.target_aspect_ratios.filter((item): item is string => typeof item === "string") : ["16:9"],
      output_root: stringValue(input.output_root, "renders"),
      ...(typeof input.creative_brief === "string" ? { creative_brief: input.creative_brief } : {}),
      scenes: [], notes: {},
    };
    await this.#projects.set(project);
    return project;
  }

  async apply(id: string, baseRevision: number, operations: Record<string, unknown>[]): Promise<ProjectManifest> {
    const project = this.get(id);
    if (project.revision !== baseRevision) throw new SidecarError("revision_conflict", `project revision is ${project.revision}, not ${baseRevision}`, { current_revision: project.revision });
    const next = structuredClone(project);
    for (const operation of operations) applyOperation(next, operation);
    next.revision++;
    next.updated_at = new Date().toISOString();
    await this.#projects.set(next);
    return next;
  }

  validate(project: ProjectManifest): { valid: boolean; errors: string[]; warnings: string[] } {
    const errors: string[] = [];
    const warnings: string[] = [];
    const sceneIds = new Set<string>();
    for (const [index, scene] of project.scenes.entries()) {
      if (!isObject(scene) || typeof scene.id !== "string") errors.push(`scene ${index} has no stable id`);
      else if (sceneIds.has(scene.id)) errors.push(`duplicate scene id ${scene.id}`);
      else sceneIds.add(scene.id);
      const directShots = isObject(scene) && Array.isArray(scene.shots) ? scene.shots : [];
      validateShots(directShots, `scene ${index}`, project, this.#artifacts, errors, warnings);
      const takes = isObject(scene) && Array.isArray(scene.takes) ? scene.takes : [];
      const takeIds = new Set<string>();
      for (const [takeIndex, take] of takes.entries()) {
        if (!isObject(take) || typeof take.id !== "string") { errors.push(`scene ${index} take ${takeIndex} has no stable id`); continue; }
        if (takeIds.has(take.id)) errors.push(`duplicate take id ${take.id} in scene ${String(scene.id)}`);
        takeIds.add(take.id);
        if (!take.replay_id && !take.replay_path) warnings.push(`take ${take.id} has no finalized replay reference`);
        validateShots(Array.isArray(take.shots) ? take.shots : [], `take ${take.id}`, project, this.#artifacts, errors, warnings);
      }
    }
    if (project.scenes.length === 0) warnings.push("project has no scenes");
    return { valid: errors.length === 0, errors, warnings };
  }

  async exportHandoff(project: ProjectManifest): Promise<{ path: string; sha256: string; manifest: Record<string, unknown> }> {
    const validation = this.validate(project);
    if (!validation.valid) throw new SidecarError("project_invalid", "project must pass validation before export", { errors: validation.errors });
    const referenced = referencedArtifacts(project, this.#artifacts);
    const manifest = {
      format: "replay-mcp.handoff/1", exported_at: new Date().toISOString(),
      project_id: project.id, project_revision: project.revision,
      frame_rate: project.frame_rate, resolution: project.resolution,
      scenes: project.scenes,
      artifacts: referenced.map((artifact) => ({
        id: artifact.id, path: artifact.path, mime_type: artifact.mime_type,
        size: artifact.size, sha256: artifact.sha256,
      })),
      validation,
    };
    const path = join(this.#artifacts.localRoot, "handoffs", `${project.id}-r${project.revision}.json`);
    await writeJsonAtomic(path, manifest);
    const sha256 = createHash("sha256").update(`${JSON.stringify(manifest, null, 2)}\n`).digest("hex");
    return { path, sha256, manifest };
  }
}

function applyOperation(project: ProjectManifest, operation: Record<string, unknown>): void {
  const op = operation.op;
  if (op === "set") {
    const field = operation.field;
    if (typeof field !== "string" || !["title", "creative_brief", "notes", "target_aspect_ratios", "output_root"].includes(field)) throw new SidecarError("invalid_request", "unsupported project set field");
    (project as unknown as Record<string, unknown>)[field] = operation.value;
  } else if (op === "upsert_scene") {
    if (!isObject(operation.scene) || typeof operation.scene.id !== "string") throw new SidecarError("invalid_request", "upsert_scene requires scene.id");
    const scene = operation.scene;
    const sceneId = scene.id;
    const index = project.scenes.findIndex((item) => item.id === sceneId);
    if (index < 0) project.scenes.push(scene);
    else project.scenes[index] = scene;
  } else if (op === "remove_scene") {
    if (typeof operation.scene_id !== "string") throw new SidecarError("invalid_request", "remove_scene requires scene_id");
    project.scenes = project.scenes.filter((item) => item.id !== operation.scene_id);
  } else if (op === "reorder_scenes") {
    if (!Array.isArray(operation.scene_ids)) throw new SidecarError("invalid_request", "reorder_scenes requires scene_ids");
    const byId = new Map(project.scenes.map((scene) => [scene.id, scene]));
    const reordered = operation.scene_ids.map((id) => byId.get(String(id))).filter((value): value is Record<string, unknown> => value !== undefined);
    if (reordered.length !== project.scenes.length || new Set(operation.scene_ids).size !== project.scenes.length) throw new SidecarError("invalid_request", "scene_ids must contain every scene exactly once");
    project.scenes = reordered;
  } else if (op === "upsert_take") {
    const scene = requiredScene(project, operation.scene_id);
    if (!isObject(operation.take) || typeof operation.take.id !== "string") throw new SidecarError("invalid_request", "upsert_take requires take.id");
    const takes = ensureObjectArray(scene, "takes");
    upsertById(takes, operation.take);
  } else if (op === "remove_take") {
    const scene = requiredScene(project, operation.scene_id);
    if (typeof operation.take_id !== "string") throw new SidecarError("invalid_request", "remove_take requires take_id");
    scene.takes = ensureObjectArray(scene, "takes").filter((take) => take.id !== operation.take_id);
  } else if (op === "reorder_takes") {
    const scene = requiredScene(project, operation.scene_id);
    scene.takes = reorderByIds(ensureObjectArray(scene, "takes"), operation.take_ids, "take_ids");
  } else if (op === "upsert_shot") {
    const parent = shotParent(project, operation.scene_id, operation.take_id);
    if (!isObject(operation.shot) || typeof operation.shot.id !== "string") throw new SidecarError("invalid_request", "upsert_shot requires shot.id");
    upsertById(ensureObjectArray(parent, "shots"), operation.shot);
  } else if (op === "remove_shot") {
    const parent = shotParent(project, operation.scene_id, operation.take_id);
    if (typeof operation.shot_id !== "string") throw new SidecarError("invalid_request", "remove_shot requires shot_id");
    parent.shots = ensureObjectArray(parent, "shots").filter((shot) => shot.id !== operation.shot_id);
  } else if (op === "reorder_shots") {
    const parent = shotParent(project, operation.scene_id, operation.take_id);
    parent.shots = reorderByIds(ensureObjectArray(parent, "shots"), operation.shot_ids, "shot_ids");
  } else if (op === "set_note") {
    if (typeof operation.key !== "string" || !operation.key.trim()) throw new SidecarError("invalid_request", "set_note requires key");
    project.notes[operation.key] = operation.value;
  } else throw new SidecarError("invalid_request", `unsupported project operation: ${String(op)}`);
}

function requiredScene(project: ProjectManifest, sceneId: unknown): Record<string, unknown> {
  if (typeof sceneId !== "string") throw new SidecarError("invalid_request", "scene_id is required");
  const scene = project.scenes.find((item) => item.id === sceneId);
  if (!scene) throw new SidecarError("invalid_request", `scene ${sceneId} was not found`);
  return scene;
}

function shotParent(project: ProjectManifest, sceneId: unknown, takeId: unknown): Record<string, unknown> {
  const scene = requiredScene(project, sceneId);
  if (takeId === undefined) return scene;
  if (typeof takeId !== "string") throw new SidecarError("invalid_request", "take_id must be a string");
  const take = ensureObjectArray(scene, "takes").find((item) => item.id === takeId);
  if (!take) throw new SidecarError("invalid_request", `take ${takeId} was not found`);
  return take;
}

function ensureObjectArray(parent: Record<string, unknown>, field: string): Record<string, unknown>[] {
  if (parent[field] === undefined) parent[field] = [];
  if (!Array.isArray(parent[field]) || !(parent[field] as unknown[]).every(isObject)) throw new SidecarError("invalid_request", `${field} must be an array of objects`);
  return parent[field] as Record<string, unknown>[];
}

function upsertById(items: Record<string, unknown>[], value: Record<string, unknown>): void {
  const index = items.findIndex((item) => item.id === value.id);
  if (index < 0) items.push(value); else items[index] = value;
}

function reorderByIds(items: Record<string, unknown>[], ids: unknown, field: string): Record<string, unknown>[] {
  if (!Array.isArray(ids) || ids.some((id) => typeof id !== "string")) throw new SidecarError("invalid_request", `${field} must be an array of ids`);
  const byId = new Map(items.map((item) => [item.id, item]));
  const reordered = ids.map((id) => byId.get(id)).filter((item): item is Record<string, unknown> => item !== undefined);
  if (reordered.length !== items.length || new Set(ids).size !== items.length) throw new SidecarError("invalid_request", `${field} must contain every id exactly once`);
  return reordered;
}

function validateShots(
  shots: unknown[], context: string, project: ProjectManifest, artifacts: ArtifactStore,
  errors: string[], warnings: string[],
): void {
  const ids = new Set<string>();
  for (const [index, shot] of shots.entries()) {
    if (!isObject(shot) || typeof shot.id !== "string") { errors.push(`${context} shot ${index} has no stable id`); continue; }
    if (ids.has(shot.id)) errors.push(`duplicate shot id ${shot.id} in ${context}`);
    ids.add(shot.id);
    if (typeof shot.in_us === "number" && typeof shot.out_us === "number" && shot.out_us <= shot.in_us) errors.push(`shot ${shot.id} has an invalid range`);
    if (typeof shot.frame_rate === "number" && shot.frame_rate !== project.frame_rate) errors.push(`shot ${shot.id} frame rate conflicts with project frame rate`);
    if (!shot.render_artifact_id) warnings.push(`shot ${shot.id} is not rendered`);
    else if (typeof shot.render_artifact_id !== "string" || !artifacts.get(shot.render_artifact_id)) errors.push(`shot ${shot.id} references a missing render artifact`);
  }
}

function referencedArtifacts(project: ProjectManifest, store: ArtifactStore) {
  const ids = new Set<string>();
  const visit = (value: unknown): void => {
    if (Array.isArray(value)) value.forEach(visit);
    else if (isObject(value)) for (const [key, child] of Object.entries(value)) {
      if (key.endsWith("artifact_id") && typeof child === "string") ids.add(child);
      visit(child);
    }
  };
  visit(project.scenes);
  return [...ids].map((id) => store.get(id)).filter((item) => item !== undefined);
}

function isObject(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value); }
function stringValue(value: unknown, fallback: string): string { return typeof value === "string" && value.trim() ? value.trim() : fallback; }
function numberValue(value: unknown, fallback: number): number { return typeof value === "number" && Number.isFinite(value) ? value : fallback; }
