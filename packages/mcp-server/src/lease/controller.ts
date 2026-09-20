import { BridgeClient, BridgeRpcError } from "../bridge/client.js";
import { SidecarError } from "../bridge/discovery.js";
import { LeaseSchema, type Lease } from "../types.js";
import type { AuditLog } from "../persistence.js";

interface OwnedLease {
  lease: Lease;
  client: BridgeClient;
  timer: NodeJS.Timeout | undefined;
  heartbeatInFlight: boolean;
  heartbeatFailures: number;
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
    const existing = this.#owned.get(client.descriptor.instanceId);
    if (existing) return existing.lease;
    const lease = LeaseSchema.parse(await client.call("lease.acquire", { owner_label: ownerLabel }));
    const intervalMs = client.hello?.lease_policy.heartbeat_interval_ms;
    if (!intervalMs) throw new SidecarError("protocol_mismatch", "bridge did not advertise lease heartbeat policy");
    const owned: OwnedLease = {
      lease, client, heartbeatInFlight: false, heartbeatFailures: 0,
      timer: undefined, active: 0, lastActivity: Date.now(), jobs: new Set(),
    };
    this.#owned.set(client.descriptor.instanceId, owned);
    this.#scheduleHeartbeat(client.descriptor.instanceId, intervalMs);
    client.once("disconnect", () => this.#lose(client.descriptor.instanceId, "bridge disconnected"));
    await this.#audit.append("lease.acquired", { instance_id: client.descriptor.instanceId, fence: lease.fence, owner_label: lease.owner_label });
    return lease;
  }

  async release(client: BridgeClient): Promise<{ released: true }> {
    const owned = this.#owned.get(client.descriptor.instanceId);
    if (!owned) throw new SidecarError("control_required", "this MCP session does not own the instance lease");
    this.#owned.delete(client.descriptor.instanceId);
    if (owned.timer) clearTimeout(owned.timer);
    try {
      await client.call("lease.release", { lease_id: owned.lease.lease_id, fence: owned.lease.fence }, { deadlineMs: 3_000 });
    } finally {
      await this.#audit.append("lease.released", { instance_id: client.descriptor.instanceId, fence: owned.lease.fence });
    }
    return { released: true };
  }

  async mutate(client: BridgeClient, method: string, params: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
    const owned = this.#owned.get(client.descriptor.instanceId);
    if (!owned) throw new SidecarError("control_required", "call control_acquire once before the first mutation in this directing or replay-editing phase; do not call it before every edit");
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

  #scheduleHeartbeat(instanceId: string, delayMs: number): void {
    const owned = this.#owned.get(instanceId);
    if (!owned) return;
    if (owned.timer) clearTimeout(owned.timer);
    owned.timer = setTimeout(() => {
      owned.timer = undefined;
      void this.#heartbeat(instanceId);
    }, delayMs);
    owned.timer.unref();
  }

  async #heartbeat(instanceId: string): Promise<void> {
    const owned = this.#owned.get(instanceId);
    if (!owned || owned.heartbeatInFlight) return;
    owned.heartbeatInFlight = true;
    const policy = owned.client.hello?.lease_policy;
    const intervalMs = policy?.heartbeat_interval_ms ?? 5_000;
    const ttlMs = policy?.ttl_ms ?? 15_000;
    const deadlineMs = Math.max(500, Math.min(intervalMs - 250, Math.floor(ttlMs / 3)));
    try {
      const lease = LeaseSchema.parse(await owned.client.call("lease.heartbeat", {
        lease_id: owned.lease.lease_id,
        fence: owned.lease.fence,
        active: owned.active > 0 || owned.jobs.size > 0 || Date.now() - owned.lastActivity < 2_000,
      }, { deadlineMs }));
      if (this.#owned.get(instanceId) !== owned) return;
      owned.lease = lease;
      owned.heartbeatFailures = 0;
      this.#scheduleHeartbeat(instanceId, intervalMs);
    } catch (error) {
      if (this.#owned.get(instanceId) !== owned) return;
      const terminalBridgeError = error instanceof BridgeRpcError && !["timeout", "internal_error"].includes(error.code);
      const expiresAt = Date.parse(owned.lease.expires_at);
      const remainingMs = Number.isFinite(expiresAt) ? expiresAt - Date.now() : 0;
      if (terminalBridgeError || !owned.client.connected || remainingMs <= 250) {
        this.#lose(instanceId, error instanceof Error ? error.message : String(error));
        return;
      }
      owned.heartbeatFailures++;
      void this.#audit.append("lease.heartbeat_retry", {
        instance_id: instanceId,
        fence: owned.lease.fence,
        attempt: owned.heartbeatFailures,
        remaining_ms: remainingMs,
        reason: error instanceof Error ? error.message : String(error),
      });
      this.#scheduleHeartbeat(instanceId, Math.max(250, Math.min(1_000, Math.floor(remainingMs / 3))));
    } finally {
      owned.heartbeatInFlight = false;
    }
  }

  #lose(instanceId: string, reason: string): void {
    const owned = this.#owned.get(instanceId);
    if (!owned) return;
    if (owned.timer) clearTimeout(owned.timer);
    this.#owned.delete(instanceId);
    void this.#audit.append("lease.lost", { instance_id: instanceId, reason });
  }
}

function deadlineFrom(params: Record<string, unknown>): number {
  const value = params.timeout_ms ?? params.deadline_ms;
  return typeof value === "number" && Number.isFinite(value) ? Math.max(250, Math.min(300_000, Math.trunc(value))) : 30_000;
}
