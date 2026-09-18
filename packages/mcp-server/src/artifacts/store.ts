import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { lstat, mkdir, readFile, realpath } from "node:fs/promises";
import { basename, join, resolve, sep } from "node:path";
import { ArtifactDescriptorSchema, type ArtifactDescriptor, type JsonObject } from "../types.js";
import { JsonCollection } from "../persistence.js";
import { SidecarError } from "../bridge/discovery.js";

export interface ArtifactRecord {
  id: string;
  storage_version: 1;
  instance_id: string;
  path: string;
  filename: string;
  mime_type: string;
  size: number;
  sha256: string;
  complete: boolean;
  created_at: string;
  project_id?: string;
  scene_id?: string;
  shot_id?: string;
  take_id?: string;
  provenance?: JsonObject;
  width?: number;
  height?: number;
}

export class ArtifactStore {
  readonly #records: JsonCollection<ArtifactRecord>;
  readonly #maxInlineBytes: number;
  readonly localRoot: string;

  constructor(dataDir: string, maxInlineBytes = 20 * 1024 * 1024) {
    this.#records = new JsonCollection(dataDir, "artifacts/index.json");
    this.#maxInlineBytes = maxInlineBytes;
    this.localRoot = join(dataDir, "artifacts", "files");
  }
  async load(): Promise<void> { await mkdir(this.localRoot, { recursive: true }); await this.#records.load(); }
  list(): ArtifactRecord[] { return this.#records.values(); }
  get(id: string): ArtifactRecord | undefined { return this.#records.get(id); }

  async register(
    descriptorInput: unknown,
    instanceId: string,
    allowedRoots: string[],
    metadata: Partial<Pick<ArtifactRecord, "project_id" | "scene_id" | "shot_id" | "take_id" | "provenance">> = {},
    persist = true,
  ): Promise<ArtifactRecord> {
    const descriptor = ArtifactDescriptorSchema.parse(descriptorInput);
    if (!descriptor.complete) throw new SidecarError("artifact_incomplete", "incomplete artifacts cannot be registered as valid outputs");
    const path = await confinedRealPath(descriptor.path, allowedRoots);
    const stats = await lstat(path);
    if (!stats.isFile()) throw new SidecarError("artifact_invalid", "artifact path is not a regular file");
    if (stats.size !== descriptor.size) throw new SidecarError("artifact_checksum_failed", "artifact size does not match bridge descriptor");
    const sha256 = await hashFile(path);
    if (sha256 !== descriptor.sha256.toLowerCase()) throw new SidecarError("artifact_checksum_failed", "artifact SHA-256 does not match bridge descriptor");
    const id = `artifact_${sha256.slice(0, 24)}`;
    const record: ArtifactRecord = {
      id, storage_version: 1, instance_id: instanceId, path,
      filename: basename(path), mime_type: descriptor.mime_type,
      size: descriptor.size, sha256, complete: true,
      created_at: new Date().toISOString(),
      ...(descriptor.width === undefined ? {} : { width: descriptor.width }),
      ...(descriptor.height === undefined ? {} : { height: descriptor.height }),
      ...metadata,
    };
    if (persist) await this.#records.set(record);
    return record;
  }

  async registerLocal(path: string, mimeType: string, metadata: Partial<Pick<ArtifactRecord, "project_id" | "scene_id" | "shot_id" | "take_id" | "provenance">> = {}): Promise<ArtifactRecord> {
    const canonical = await confinedRealPath(path, [this.localRoot]);
    const stats = await lstat(canonical);
    const sha256 = await hashFile(canonical);
    return await this.register({ path: canonical, mime_type: mimeType, size: stats.size, sha256, complete: true }, "sidecar", [this.localRoot], metadata);
  }

  async inlineImage(record: ArtifactRecord): Promise<{ type: "image"; data: string; mimeType: string }> {
    if (!record.mime_type.startsWith("image/")) throw new SidecarError("artifact_invalid", "artifact is not an image");
    if (record.size > this.#maxInlineBytes) throw new SidecarError("artifact_too_large", "image exceeds the inline MCP limit");
    await this.#reverify(record);
    return { type: "image", data: (await readFile(record.path)).toString("base64"), mimeType: record.mime_type };
  }

  async resource(record: ArtifactRecord): Promise<{ uri: string; mimeType: string; blob: string } | { uri: string; mimeType: string; text: string }> {
    await this.#reverify(record);
    const data = await readFile(record.path);
    if (record.mime_type.startsWith("text/") || record.mime_type === "application/json") {
      return { uri: `replay-mcp://artifact/${record.id}`, mimeType: record.mime_type, text: data.toString("utf8") };
    }
    return { uri: `replay-mcp://artifact/${record.id}`, mimeType: record.mime_type, blob: data.toString("base64") };
  }

  async #reverify(record: ArtifactRecord): Promise<void> {
    const stats = await lstat(record.path);
    if (!stats.isFile() || stats.size !== record.size || await hashFile(record.path) !== record.sha256) {
      throw new SidecarError("artifact_checksum_failed", "artifact changed after registration");
    }
  }
}

export async function confinedRealPath(path: string, allowedRoots: string[]): Promise<string> {
  const canonical = await realpath(path);
  for (const root of allowedRoots) {
    let canonicalRoot: string;
    try { canonicalRoot = await realpath(root); } catch { canonicalRoot = resolve(root); }
    if (canonical === canonicalRoot || canonical.startsWith(`${canonicalRoot}${sep}`)) return canonical;
  }
  throw new SidecarError("artifact_path_denied", "artifact path is outside the authenticated instance roots");
}

async function hashFile(path: string): Promise<string> {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk as Buffer);
  return hash.digest("hex");
}

export function findArtifactDescriptors(value: unknown): ArtifactDescriptor[] {
  const found: ArtifactDescriptor[] = [];
  const visit = (node: unknown): void => {
    const parsed = ArtifactDescriptorSchema.safeParse(node);
    if (parsed.success) { found.push(parsed.data); return; }
    if (Array.isArray(node)) for (const item of node) visit(item);
    else if (node && typeof node === "object") for (const item of Object.values(node)) visit(item);
  };
  visit(value);
  return found;
}
