import { ArtifactStore, findArtifactDescriptors, type ArtifactRecord } from "./artifacts/store.js";
import { DiscoveryManager, SidecarError } from "./bridge/discovery.js";
import type { BridgeClient } from "./bridge/client.js";
import type { CliOptions } from "./config.js";
import { JobStore } from "./jobs/store.js";
import { LeaseController } from "./lease/controller.js";
import { AuditLog } from "./persistence.js";
import { ProjectStore } from "./projects/store.js";
import { asObject } from "./types.js";

export class ReplayMcpRuntime {
  readonly sessionId = crypto.randomUUID();
  readonly options: CliOptions;
  readonly audit: AuditLog;
  readonly discovery: DiscoveryManager;
  readonly leases: LeaseController;
  readonly artifacts: ArtifactStore;
  readonly jobs: JobStore;
  readonly projects: ProjectStore;
  readonly recordingContexts = new Map<string, { project_id?: string; scene_id?: string; take_id?: string }>();
  #started = false;

  constructor(options: CliOptions) {
    this.options = options;
    this.audit = new AuditLog(options.dataDir);
    this.artifacts = new ArtifactStore(options.dataDir);
    this.leases = new LeaseController(this.audit);
    this.jobs = new JobStore(options.dataDir, this.artifacts, this.audit, this.sessionId, {
      started: (instanceId, jobId) => this.leases.holdJob(instanceId, jobId),
      finished: (instanceId, jobId) => this.leases.releaseJob(instanceId, jobId),
    });
    this.projects = new ProjectStore(options.dataDir, this.artifacts);
    this.discovery = new DiscoveryManager(options.gameDirs, options.guessedGameDirs, options.discoveryIntervalMs, this.audit, {
      ...(options.configFiles ? { configFiles: options.configFiles } : {}),
      ...(options.gameDirSources ? { gameDirSources: options.gameDirSources } : {}),
    });
  }

  async start(): Promise<void> {
    if (this.#started) return;
    await Promise.all([this.artifacts.load(), this.jobs.load(), this.projects.load()]);
    await this.discovery.start();
    this.#started = true;
  }

  client(instanceId?: string): BridgeClient {
    const client = this.discovery.select(instanceId);
    this.jobs.attachBridgeEvents(client);
    return client;
  }

  async read(method: string, args: Record<string, unknown>): Promise<{ result: unknown; client: BridgeClient }> {
    const client = this.client(typeof args.instance_id === "string" ? args.instance_id : undefined);
    const params = withoutInstance(args);
    const result = await client.call(method, params, { deadlineMs: deadline(params) });
    return { result, client };
  }

  async mutate(method: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<{ result: unknown; client: BridgeClient }> {
    const client = this.client(typeof args.instance_id === "string" ? args.instance_id : undefined);
    const params = withoutInstance(args);
    const result = await this.leases.mutate(client, method, params, signal);
    return { result, client };
  }

  async registerArtifacts(
    result: unknown,
    client: BridgeClient,
    metadata: Partial<Pick<ArtifactRecord, "project_id" | "scene_id" | "shot_id" | "take_id" | "provenance">> = {},
    persist = true,
  ): Promise<ArtifactRecord[]> {
    const descriptors = findArtifactDescriptors(result);
    const registered: ArtifactRecord[] = [];
    for (const descriptor of descriptors) {
      registered.push(await this.artifacts.register(descriptor, client.descriptor.instanceId, client.hello?.allowed_artifact_roots ?? [], metadata, persist));
    }
    return registered;
  }

  async close(): Promise<void> {
    await this.leases.close();
    await this.discovery.close();
    await this.audit.append("sidecar.stopped");
  }
}

function withoutInstance(args: Record<string, unknown>): Record<string, unknown> {
  const { instance_id: _, ...params } = args;
  return params;
}

function deadline(params: Record<string, unknown>): number {
  const value = params.timeout_ms ?? params.deadline_ms;
  return typeof value === "number" && Number.isFinite(value) ? Math.max(250, Math.min(300_000, Math.trunc(value))) : 30_000;
}

export function objectResult(value: unknown): Record<string, unknown> { return asObject(value) as Record<string, unknown>; }
export { SidecarError };
