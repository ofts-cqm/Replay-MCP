import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { realpath, stat } from "node:fs/promises";
import { SidecarError } from "../bridge/discovery.js";

export const CLIP_MARKER_PREFIX = "replay_mcp:clip:v1:";
const CLIP_MARKER = /^replay_mcp:clip:v1:([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}):(start|end|revoke)$/i;

export interface ReplayMarkerRecord { name: string; time_us: number }
export interface AcceptedClip { clip_id: string; replay_in_us: number; replay_out_us: number }
export interface ClipDiagnostic {
  clip_id?: string;
  status: "revoked" | "incomplete" | "duplicate" | "malformed";
  code: string;
  message: string;
  markers: ReplayMarkerRecord[];
}
export interface CapturedTake {
  id: string;
  take_id: string;
  project_id: string;
  scene_id: string;
  format: "replay-mcp.captured-take/1";
  provenance: "player" | "agent";
  replay_id: string;
  replay_path: string;
  replay_sha256: string;
  replay_size: number;
  duration_us: number;
  compatibility: Record<string, unknown>;
  accepted_clips: AcceptedClip[];
  diagnostics: ClipDiagnostic[];
  shots: Record<string, unknown>[];
}

export function parseClipMarkers(markers: unknown): { accepted: AcceptedClip[]; diagnostics: ClipDiagnostic[] } {
  if (!Array.isArray(markers)) throw new SidecarError("invalid_replay_metadata", "replay metadata did not contain a marker array");
  const groups = new Map<string, { start: ReplayMarkerRecord[]; end: ReplayMarkerRecord[]; revoke: ReplayMarkerRecord[] }>();
  const diagnostics: ClipDiagnostic[] = [];
  for (const raw of markers) {
    if (!isObject(raw) || typeof raw.name !== "string") {
      diagnostics.push({ status: "malformed", code: "invalid_marker_record", message: "marker record has no string name", markers: [] });
      continue;
    }
    if (!raw.name.startsWith("replay_mcp:clip:")) continue;
    const match = CLIP_MARKER.exec(raw.name);
    const record = typeof raw.time_us === "number" && Number.isSafeInteger(raw.time_us) && raw.time_us >= 0
      ? { name: raw.name, time_us: raw.time_us } : undefined;
    if (!match || !record) {
      diagnostics.push({
        ...(match ? { clip_id: match[1]!.toLowerCase() } : {}), status: "malformed", code: "invalid_clip_marker",
        message: "clip marker does not match replay_mcp:clip:v1:<uuid>:<start|end|revoke> with a non-negative integer time_us",
        markers: record ? [record] : [],
      });
      continue;
    }
    const clipId = match[1]!.toLowerCase();
    const kind = match[2]!.toLowerCase() as "start" | "end" | "revoke";
    const group = groups.get(clipId) ?? { start: [], end: [], revoke: [] };
    group[kind].push(record); groups.set(clipId, group);
  }

  const accepted: AcceptedClip[] = [];
  for (const [clipId, group] of groups) {
    const all = [...group.start, ...group.end, ...group.revoke].sort((a, b) => a.time_us - b.time_us || a.name.localeCompare(b.name));
    if (group.start.length > 1 || group.end.length > 1 || group.revoke.length > 1) {
      diagnostics.push({ clip_id: clipId, status: "duplicate", code: "duplicate_clip_marker", message: "clip has duplicate start, end, or revoke markers", markers: all });
      continue;
    }
    if (group.revoke.length === 1) {
      diagnostics.push({ clip_id: clipId, status: "revoked", code: "clip_revoked", message: "clip was intentionally revoked and is not includable", markers: all });
      continue;
    }
    if (group.start.length !== 1 || group.end.length !== 1) {
      diagnostics.push({ clip_id: clipId, status: "incomplete", code: "missing_clip_endpoint", message: "clip does not have exactly one start and one end marker", markers: all });
      continue;
    }
    const replayIn = group.start[0]!.time_us, replayOut = group.end[0]!.time_us;
    if (replayOut <= replayIn) {
      diagnostics.push({ clip_id: clipId, status: "malformed", code: "invalid_clip_range", message: "clip end must be later than its start", markers: all });
      continue;
    }
    accepted.push({ clip_id: clipId, replay_in_us: replayIn, replay_out_us: replayOut });
  }
  accepted.sort((a, b) => a.replay_in_us - b.replay_in_us || a.clip_id.localeCompare(b.clip_id));
  diagnostics.sort((a, b) => (a.clip_id ?? "").localeCompare(b.clip_id ?? "") || a.code.localeCompare(b.code));
  return { accepted, diagnostics };
}

export async function verifyReplaySource(metadata: Record<string, unknown>): Promise<{
  path: string; sha256: string; size: number; duration_us: number; compatibility: Record<string, unknown>;
}> {
  if (metadata.finalized !== true || metadata.source_immutable !== true) throw new SidecarError("replay_not_finalized", "replay metadata does not identify a finalized immutable source");
  if (typeof metadata.path !== "string" || typeof metadata.sha256 !== "string" || !/^[a-f0-9]{64}$/i.test(metadata.sha256)) {
    throw new SidecarError("invalid_replay_metadata", "replay metadata is missing its path or SHA-256 identity");
  }
  if (typeof metadata.duration_us !== "number" || !Number.isSafeInteger(metadata.duration_us) || metadata.duration_us < 0) {
    throw new SidecarError("invalid_replay_metadata", "replay duration_us must be a non-negative integer");
  }
  const path = await realpath(metadata.path);
  const before = await stat(path);
  if (!before.isFile()) throw new SidecarError("invalid_replay_metadata", "replay source is not a regular file");
  if (typeof metadata.size === "number" && metadata.size !== before.size) throw new SidecarError("replay_changed", "replay size changed after bridge metadata was read");
  const sha256 = await hashFile(path);
  const after = await stat(path);
  if (before.size !== after.size || before.mtimeMs !== after.mtimeMs || sha256 !== metadata.sha256.toLowerCase()) {
    throw new SidecarError("replay_changed", "replay changed while its immutable source identity was being verified");
  }
  return {
    path, sha256, size: after.size, duration_us: metadata.duration_us,
    compatibility: Object.fromEntries(["minecraft_version", "file_format", "file_format_version", "created_at_ms", "server"]
      .filter((key) => metadata[key] !== undefined).map((key) => [key, metadata[key]])),
  };
}

export function capturedTakeId(sha256: string): string { return `take_${sha256.slice(0, 24)}`; }
export function capturedShotId(sha256: string, clipId: string): string {
  return `shot_${createHash("sha256").update(`${sha256}:${clipId}`).digest("hex").slice(0, 24)}`;
}

async function hashFile(path: string): Promise<string> {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk as Buffer);
  return hash.digest("hex");
}

function isObject(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value); }
