import { JsonCollection, type AuditLog } from "../persistence.js";
import type { ArtifactRecord, ArtifactStore } from "../artifacts/store.js";
import type { BridgeClient } from "../bridge/client.js";
import { SidecarError } from "../bridge/discovery.js";

export type JobStatus = "queued" | "running" | "completed" | "failed" | "cancelled";
export interface JobRecord {
  id: string;
  storage_version: 1;
  kind: "render" | "still" | "preview" | "export" | "analysis";
  status: JobStatus;
  created_at: string;
  updated_at: string;
  duration_ms?: number;
  instance_id?: string;
  project_id?: string;
  progress: number;
  cancellable: boolean;
  bridge_backed: boolean;
  bridge_job_id?: string;
  logs: string[];
  warnings: string[];
  result_artifact_ids: string[];
  failure?: { code: string; message: string };
  result?: Record<string, unknown>;
  owner_session_id?: string;
}

export class JobStore {
  readonly #jobs: JsonCollection<JobRecord>;
  readonly #artifacts: ArtifactStore;
  readonly #audit: AuditLog;
  readonly #attached = new WeakSet<BridgeClient>();
  readonly #aborters = new Map<string, AbortController>();
  readonly #sessionId: string;
  readonly #jobStarted: ((instanceId: string, jobId: string) => void) | undefined;
  readonly #jobFinished: ((instanceId: string, jobId: string) => void) | undefined;

  constructor(dataDir: string, artifacts: ArtifactStore, audit: AuditLog, sessionId: string,
              hooks: { started?: (instanceId: string, jobId: string) => void; finished?: (instanceId: string, jobId: string) => void } = {}) {
    this.#jobs = new JsonCollection(dataDir, "jobs/index.json");
    this.#artifacts = artifacts;
    this.#audit = audit;
    this.#sessionId = sessionId;
    this.#jobStarted = hooks.started; this.#jobFinished = hooks.finished;
  }
  async load(): Promise<void> {
    await this.#jobs.load();
    for (const job of this.list()) {
      if (["queued", "running"].includes(job.status)) {
        await this.update(job.id, {
          status: "failed",
          failure: { code: "sidecar_restarted", message: "the owning sidecar session ended before the job reached a terminal state" },
          logs: [...job.logs, "Marked interrupted during sidecar recovery."],
        });
      }
    }
  }
  list(): JobRecord[] { return this.#jobs.values(); }
  get(id: string): JobRecord | undefined { return this.#jobs.get(id); }

  async create(kind: JobRecord["kind"], fields: Partial<JobRecord> = {}): Promise<JobRecord> {
    const now = new Date().toISOString();
    const job: JobRecord = {
      id: crypto.randomUUID(), storage_version: 1, kind, status: "queued",
      created_at: now, updated_at: now, progress: 0, cancellable: true,
      bridge_backed: false, logs: [], warnings: [], result_artifact_ids: [],
      ...fields,
      owner_session_id: this.#sessionId,
    };
    await this.#jobs.set(job);
    await this.#audit.append("job.created", { job_id: job.id, kind });
    return job;
  }

  async update(id: string, patch: Partial<JobRecord>): Promise<JobRecord> {
    const existing = this.#jobs.get(id);
    if (!existing) throw new Error(`job ${id} not found`);
    const terminal = patch.status !== undefined && ["completed", "failed", "cancelled"].includes(patch.status);
    const becameTerminal = terminal && !["completed", "failed", "cancelled"].includes(existing.status);
    const becameRunning = patch.status === "running" && existing.status === "queued";
    const now = new Date();
    const updated = { ...existing, ...patch, id, updated_at: now.toISOString(), ...(becameTerminal ? { duration_ms: Math.max(0, now.getTime() - Date.parse(existing.created_at)) } : {}) };
    await this.#jobs.set(updated);
    if (becameRunning && existing.instance_id) this.#jobStarted?.(existing.instance_id, id);
    if (becameTerminal) {
      this.#aborters.delete(id);
      if (existing.instance_id) this.#jobFinished?.(existing.instance_id, id);
      await this.#audit.append("job.finished", { job_id: id, kind: updated.kind, status: updated.status, duration_ms: updated.duration_ms });
    }
    return updated;
  }

  createAbortController(jobId: string): AbortController {
    const controller = new AbortController();
    this.#aborters.set(jobId, controller);
    return controller;
  }

  attachBridgeEvents(client: BridgeClient): void {
    if (this.#attached.has(client)) return;
    this.#attached.add(client);
    client.on("event", (method: string, params: Record<string, unknown>) => {
      if (method.startsWith("render.")) void this.#bridgeEvent(client, method, params);
      else if (method === "recording.finalization") void this.#finalizationEvent(client, params);
      else if (method === "recording.changed") void this.#recordingEvent(client, params);
    });
  }

  async registerBridgeJob(local: JobRecord, client: BridgeClient, result: Record<string, unknown>): Promise<JobRecord> {
    const bridgeId = typeof result.job_id === "string" ? result.job_id : undefined;
    if (!bridgeId) return await this.update(local.id, { status: "failed", failure: { code: "invalid_bridge_result", message: "bridge did not return job_id" } });
    return await this.update(local.id, {
      status: "running", bridge_backed: true, bridge_job_id: bridgeId,
      instance_id: client.descriptor.instanceId, result,
    });
  }

  async cancelSidecar(job: JobRecord): Promise<JobRecord> {
    if (job.owner_session_id !== this.#sessionId) throw new SidecarError("job_control_required", "only the MCP session that created this sidecar job may cancel it");
    if (!job.cancellable || !["queued", "running"].includes(job.status)) return job;
    this.#aborters.get(job.id)?.abort();
    return await this.update(job.id, { status: "cancelled", progress: job.progress, logs: [...job.logs, "Cancellation requested."] });
  }

  async #bridgeEvent(client: BridgeClient, method: string, params: Record<string, unknown>): Promise<void> {
    const bridgeId = typeof params.job_id === "string" ? params.job_id : undefined;
    if (!bridgeId) return;
    const job = this.list().find((item) => item.bridge_job_id === bridgeId && item.instance_id === client.descriptor.instanceId);
    if (!job) return;
    if (method === "render.progress") {
      const progress = typeof params.progress === "number" ? Math.max(0, Math.min(1, params.progress)) : job.progress;
      await this.update(job.id, { progress, status: "running" });
      return;
    }
    const allowed = client.hello?.allowed_artifact_roots ?? [];
    let artifacts: ArtifactRecord[] = [];
    if (params.artifact) {
      try { artifacts = [await this.#artifacts.register(params.artifact, client.descriptor.instanceId, allowed)]; }
      catch (error) {
        await this.update(job.id, { status: "failed", failure: { code: "artifact_verification_failed", message: error instanceof Error ? error.message : String(error) } });
        return;
      }
    }
    const status: JobStatus = method === "render.completed" ? "completed" : method === "render.cancelled" ? "cancelled" : "failed";
    await this.update(job.id, {
      status, progress: status === "completed" ? 1 : job.progress,
      result_artifact_ids: artifacts.map((item) => item.id),
      result: params,
      ...(status === "failed" ? { failure: { code: "render_failed", message: typeof params.error === "string" ? params.error : "bridge render failed" } } : {}),
    });
  }

  async #recordingEvent(client: BridgeClient, params: Record<string, unknown>): Promise<void> {
    if (params.status !== "finalized" && params.status !== "recoverable") return;
    const job = this.list().filter((item) =>
      item.kind === "analysis" && item.status === "running" && item.instance_id === client.descriptor.instanceId && item.result?.purpose === "recording_finalization")
      .sort((left, right) => right.created_at.localeCompare(left.created_at))[0];
    if (!job) return;
    try {
      const artifact = params.artifact
        ? await this.#artifacts.register(params.artifact, client.descriptor.instanceId, client.hello?.allowed_artifact_roots ?? [], {
            ...(typeof job.result?.project_id === "string" ? { project_id: job.result.project_id } : {}),
            ...(typeof job.result?.scene_id === "string" ? { scene_id: job.result.scene_id } : {}),
            ...(typeof params.take_id === "string" ? { take_id: params.take_id } : typeof job.result?.take_id === "string" ? { take_id: job.result.take_id } : {}),
            provenance: { source: "recording.changed" },
          })
        : undefined;
      await this.update(job.id, {
        status: params.status === "finalized" && artifact ? "completed" : "failed",
        progress: params.status === "finalized" && artifact ? 1 : job.progress,
        result_artifact_ids: artifact ? [artifact.id] : [],
        result: { ...job.result, ...params },
        ...(params.status === "finalized" && artifact ? {} : { failure: { code: "recording_recoverable", message: "recording finalization requires manual recovery" } }),
      });
    } catch (error) {
      await this.update(job.id, { status: "failed", failure: { code: "artifact_verification_failed", message: error instanceof Error ? error.message : String(error) } });
    }
  }

  async #finalizationEvent(client: BridgeClient, params: Record<string, unknown>): Promise<void> {
    const bridgeId = typeof params.job_id === "string" ? params.job_id : undefined;
    if (!bridgeId) return;
    const job = this.list().find((item) => item.bridge_job_id === bridgeId && item.instance_id === client.descriptor.instanceId);
    if (!job) return;
    if (params.status === "running") {
      await this.update(job.id, { progress: typeof params.progress === "number" ? params.progress : job.progress, result: { ...job.result, ...params } });
      return;
    }
    let artifacts: ArtifactRecord[] = [];
    if (params.artifact) {
      try { artifacts = [await this.#artifacts.register(params.artifact, client.descriptor.instanceId, client.hello?.allowed_artifact_roots ?? [])]; }
      catch (error) {
        await this.update(job.id, { status: "failed", failure: { code: "artifact_verification_failed", message: error instanceof Error ? error.message : String(error) } }); return;
      }
    }
    const completed = params.status === "completed" && artifacts.length === 1;
    await this.update(job.id, {
      status: completed ? "completed" : "failed", progress: completed ? 1 : job.progress,
      result_artifact_ids: artifacts.map((artifact) => artifact.id), result: { ...job.result, ...params },
      ...(completed ? {} : { failure: { code: "recording_recoverable", message: typeof params.error === "string" ? params.error : "recording finalization requires recovery" } }),
    });
  }
}
