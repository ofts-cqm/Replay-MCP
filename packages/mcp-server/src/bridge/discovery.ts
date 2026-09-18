import { readFile, readdir, realpath } from "node:fs/promises";
import { join, resolve } from "node:path";
import { BridgeClient } from "./client.js";
import { InstanceDescriptorSchema, type InstanceDescriptor } from "../types.js";
import type { AuditLog } from "../persistence.js";

export interface DiscoveryResult {
  descriptor: InstanceDescriptor;
  client: BridgeClient;
}

export class DiscoveryManager {
  readonly gameDirs: string[];
  readonly guessedGameDirs: string[];
  readonly clients = new Map<string, BridgeClient>();
  readonly #audit: AuditLog;
  readonly #intervalMs: number;
  #timer?: NodeJS.Timeout;
  #running = false;

  constructor(gameDirs: string[], guessedGameDirs: string[], intervalMs: number, audit: AuditLog) {
    this.gameDirs = gameDirs.map((item) => resolve(item));
    this.guessedGameDirs = guessedGameDirs.map((item) => resolve(item));
    this.#intervalMs = intervalMs;
    this.#audit = audit;
  }

  async start(): Promise<void> {
    await this.discoverOnce();
    this.#timer = setInterval(() => { void this.discoverOnce(); }, this.#intervalMs);
    this.#timer.unref();
  }

  async discoverOnce(): Promise<void> {
    if (this.#running) return;
    this.#running = true;
    try {
      const seen = new Set<string>();
      for (const gameDir of this.gameDirs) {
        for (const descriptor of await scanDescriptors(gameDir)) {
          seen.add(descriptor.instanceId);
          if (this.clients.get(descriptor.instanceId)?.connected) continue;
          try {
            if (!pidExists(descriptor.processId)) continue;
            const instanceDir = join(gameDir, ".replay-mcp", "instances", descriptor.instanceId);
            const token = (await readFile(join(instanceDir, "token"), "ascii")).trim();
            const client = await BridgeClient.connect(descriptor, token);
            client.once("disconnect", () => {
              if (this.clients.get(descriptor.instanceId) === client) this.clients.delete(descriptor.instanceId);
            });
            this.clients.set(descriptor.instanceId, client);
            await this.#audit.append("bridge.connected", { instance_id: descriptor.instanceId, process_id: descriptor.processId });
          } catch (error) {
            await this.#audit.append("bridge.discovery_failed", {
              instance_id: descriptor.instanceId,
              message: error instanceof Error ? error.message : String(error),
            });
          }
        }
      }
      for (const [id, client] of this.clients) {
        if (!seen.has(id) || !pidExists(client.descriptor.processId)) {
          this.clients.delete(id);
          await client.close();
        }
      }
    } finally { this.#running = false; }
  }

  select(instanceId?: string): BridgeClient {
    if (instanceId) {
      const client = this.clients.get(instanceId);
      if (!client?.connected) throw new SidecarError("instance_not_found", `Minecraft instance ${instanceId} is not live`);
      return client;
    }
    const live = [...this.clients.values()].filter((client) => client.connected);
    if (live.length === 0) throw new SidecarError("instance_not_found", "no live Replay MCP Minecraft instance was discovered");
    if (live.length !== 1) throw new SidecarError("instance_ambiguous", "multiple Minecraft instances are live; pass instance_id");
    return live[0]!;
  }

  async close(): Promise<void> {
    if (this.#timer) clearInterval(this.#timer);
    await Promise.allSettled([...this.clients.values()].map(async (client) => await client.close()));
    this.clients.clear();
  }
}

export class SidecarError extends Error {
  readonly code: string;
  readonly data: Record<string, unknown>;
  constructor(code: string, message: string, data: Record<string, unknown> = {}) {
    super(message); this.name = "SidecarError"; this.code = code; this.data = data;
  }
}

export async function scanDescriptors(gameDir: string): Promise<InstanceDescriptor[]> {
  const root = join(gameDir, ".replay-mcp", "instances");
  let entries;
  try { entries = await readdir(root, { withFileTypes: true }); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }
  const descriptors: InstanceDescriptor[] = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    try {
      const path = join(root, entry.name, "bridge.json");
      const descriptor = InstanceDescriptorSchema.parse(JSON.parse(await readFile(path, "utf8")));
      const declared = await canonicalOrResolved(descriptor.gameDirectory);
      const configured = await canonicalOrResolved(gameDir);
      if (declared !== configured || descriptor.instanceId !== entry.name) continue;
      descriptors.push(descriptor);
    } catch { /* Ignore incomplete, stale, or invalid atomic publication remnants. */ }
  }
  return descriptors;
}

async function canonicalOrResolved(path: string): Promise<string> {
  try { return await realpath(path); } catch { return resolve(path); }
}

export function pidExists(pid: number): boolean {
  try { process.kill(pid, 0); return true; }
  catch (error) { return (error as NodeJS.ErrnoException).code === "EPERM"; }
}
