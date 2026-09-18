import { BridgeClient, BridgeRpcError } from "../bridge/client.js";
import { SidecarError } from "../bridge/discovery.js";
import { LeaseSchema, type Lease } from "../types.js";
import type { AuditLog } from "../persistence.js";

interface OwnedLease {
  lease: Lease;
  client: BridgeClient;
  timer: NodeJS.Timeout;
  active: number;
  lastActivity: number;
  jobs: Set<string>;
}

export class LeaseController {
  readonly #owned = new Map<string, OwnedLease>();
  readonly #audit: AuditLog;
  readonly ownerLabel: string;

  constructor(audit: AuditLog, ownerLabel = `Replay MCP sidecar ${process.pid}`) {
    this.#audit = audit;
    this.ownerLabel = ownerLabel;
  }

  get(instanceId: string): Lease | undefined { return this.#owned.get(instanceId)?.lease; }

  async acquire(client: BridgeClient, ownerLabel = this.ownerLabel): Promise<Lease> {
    if (this.#owned.has(client.descriptor.instanceId)) {
      throw new SidecarError("conflict", "this MCP session already owns control of the instance");
    }
    const lease = LeaseSchema.parse(await client.call("lease.acquire", { owner_label: ownerLabel }));
    const intervalMs = client.hello?.lease_policy.heartbeat_interval_ms;
    if (!intervalMs) throw new SidecarError("protocol_mismatch", "bridge did not advertise lease heartbeat policy");
    const owned: OwnedLease = {
      lease, client, active: 0, lastActivity: Date.now(), jobs: new Set(),
      timer: setInterval(() => { void this.#heartbeat(client.descriptor.instanceId); }, intervalMs),
    };
    owned.timer.unref();
    this.#owned.set(client.descriptor.instanceId, owned);
    client.once("disconnect", () => this.#lose(client.descriptor.instanceId, "bridge disconnected"));
    await this.#audit.append("lease.acquired", { instance_id: client.descriptor.instanceId, fence: lease.fence, owner_label: lease.owner_label });
    return lease;
  }

  async release(client: BridgeClient): Promise<{ released: true }> {
    const owned = this.#owned.get(client.descriptor.instanceId);
    if (!owned) throw new SidecarError("control_required", "this MCP session does not own the instance lease");
    this.#owned.delete(client.descriptor.instanceId);
    clearInterval(owned.timer);
    try {
      await client.call("lease.release", { lease_id: owned.lease.lease_id, fence: owned.lease.fence }, { deadlineMs: 3_000 });
    } finally {
      await this.#audit.append("lease.released", { instance_id: client.descriptor.instanceId, fence: owned.lease.fence });
    }
    return { released: true };
  }

  async mutate(client: BridgeClient, method: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
    const owned = this.#owned.get(client.descriptor.instanceId);
    if (!owned) throw new SidecarError("control_required", "control_acquire is required before this mutation");
    owned.active++;
    owned.lastActivity = Date.now();
    try {
      return await client.call(method, {
        ...params,
        request_id: typeof params.request_id === "string" ? params.request_id : crypto.randomUUID(),
        lease_id: owned.lease.lease_id,
        fence: owned.lease.fence,
      }, { deadlineMs: deadlineFrom(params), ...(signal ? { signal } : {}) });
    } catch (error) {
      if (error instanceof BridgeRpcError && ["control_required", "stale_fence"].includes(error.code)) {
        this.#lose(client.descriptor.instanceId, error.code);
      }
      throw error;
    } finally {
      owned.active--;
      owned.lastActivity = Date.now();
    }
  }

  async close(): Promise<void> {
    await Promise.allSettled([...this.#owned.values()].map(async ({ client }) => await this.release(client)));
  }

  holdJob(instanceId: string, jobId: string): void {
    const owned = this.#owned.get(instanceId);
    if (owned) { owned.jobs.add(jobId); owned.lastActivity = Date.now(); }
  }

  releaseJob(instanceId: string, jobId: string): void {
    const owned = this.#owned.get(instanceId);
    if (owned) { owned.jobs.delete(jobId); owned.lastActivity = Date.now(); }
  }

  async #heartbeat(instanceId: string): Promise<void> {
    const owned = this.#owned.get(instanceId);
    if (!owned) return;
    try {
      const lease = LeaseSchema.parse(await owned.client.call("lease.heartbeat", {
        lease_id: owned.lease.lease_id,
        fence: owned.lease.fence,
        active: owned.active > 0 || owned.jobs.size > 0 || Date.now() - owned.lastActivity < 2_000,
      }, { deadlineMs: Math.max(2_000, Math.floor((owned.client.hello?.lease_policy.ttl_ms ?? 15_000) / 2)) }));
      owned.lease = lease;
    } catch (error) {
      this.#lose(instanceId, error instanceof Error ? error.message : String(error));
    }
  }

  #lose(instanceId: string, reason: string): void {
    const owned = this.#owned.get(instanceId);
    if (!owned) return;
    clearInterval(owned.timer);
    this.#owned.delete(instanceId);
    void this.#audit.append("lease.lost", { instance_id: instanceId, reason });
  }
}

function deadlineFrom(params: Record<string, unknown>): number {
  const value = params.timeout_ms ?? params.deadline_ms;
  return typeof value === "number" && Number.isFinite(value) ? Math.max(250, Math.min(300_000, Math.trunc(value))) : 30_000;
}
