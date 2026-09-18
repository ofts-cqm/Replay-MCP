import { EventEmitter } from "node:events";
import WebSocket from "ws";
import {
  BRIDGE_PROTOCOL,
  BridgeEventSchema,
  BridgeFailureSchema,
  BridgeSuccessSchema,
  HelloSchema,
  type BridgeErrorCode,
  type Hello,
  type InstanceDescriptor,
} from "../types.js";

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (reason: unknown) => void;
  timer: NodeJS.Timeout;
}

export class BridgeRpcError extends Error {
  readonly code: BridgeErrorCode;
  readonly data: Record<string, unknown>;
  constructor(code: BridgeErrorCode, message: string, data: Record<string, unknown> = {}) {
    super(message);
    this.name = "BridgeRpcError";
    this.code = code;
    this.data = data;
  }
}

export class BridgeClient extends EventEmitter {
  readonly descriptor: InstanceDescriptor;
  readonly token: string;
  readonly #socket: WebSocket;
  readonly #pending = new Map<string, PendingCall>();
  #nextId = 1;
  #closed = false;
  hello?: Hello;

  private constructor(descriptor: InstanceDescriptor, token: string, socket: WebSocket) {
    super();
    this.descriptor = descriptor;
    this.token = token;
    this.#socket = socket;
    socket.on("message", (data, binary) => this.#message(data, binary));
    socket.on("close", () => this.#disconnected(new BridgeRpcError("internal_error", "bridge disconnected")));
    socket.on("error", (error) => this.#disconnected(error));
  }

  static async connect(descriptor: InstanceDescriptor, token: string, timeoutMs = 5_000): Promise<BridgeClient> {
    if (!/^[a-f0-9]{64}$/i.test(token)) throw new Error("invalid bridge token");
    const socket = new WebSocket(`ws://127.0.0.1:${descriptor.port}/bridge`, {
      headers: { "X-Replay-MCP-Token": token },
      handshakeTimeout: timeoutMs,
      maxPayload: 8 * 1024 * 1024,
    });
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("bridge connection timed out")), timeoutMs);
      socket.once("open", () => { clearTimeout(timer); resolve(); });
      socket.once("error", (error) => { clearTimeout(timer); reject(error); });
    });
    const client = new BridgeClient(descriptor, token, socket);
    try {
      const result = await client.call("system.hello", {
        protocol: BRIDGE_PROTOCOL,
        instance_id: descriptor.instanceId,
      }, { deadlineMs: timeoutMs });
      const hello = HelloSchema.parse(result);
      if (hello.instance_id !== descriptor.instanceId || hello.process_id !== descriptor.processId) {
        throw new Error("bridge hello identity does not match discovery descriptor");
      }
      client.hello = hello;
      return client;
    } catch (error) {
      await client.close();
      throw error;
    }
  }

  get connected(): boolean { return !this.#closed && this.#socket.readyState === WebSocket.OPEN && this.hello !== undefined; }

  async call(
    method: string,
    params: Record<string, unknown> = {},
    options: { deadlineMs?: number; signal?: AbortSignal } = {},
  ): Promise<unknown> {
    if (this.#closed || this.#socket.readyState !== WebSocket.OPEN) {
      throw new BridgeRpcError("internal_error", "bridge is not connected");
    }
    const id = String(this.#nextId++);
    const deadlineMs = options.deadlineMs ?? 30_000;
    return await new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(new BridgeRpcError("timeout", `${method} exceeded its ${deadlineMs}ms deadline`));
      }, deadlineMs + 100);
      const abort = () => {
        clearTimeout(timer);
        this.#pending.delete(id);
        reject(new BridgeRpcError("cancelled", `${method} was cancelled`));
        this.#sendNotificationCancel(id);
      };
      if (options.signal?.aborted) return abort();
      options.signal?.addEventListener("abort", abort, { once: true });
      this.#pending.set(id, {
        timer,
        resolve: (value) => { options.signal?.removeEventListener("abort", abort); resolve(value); },
        reject: (reason) => { options.signal?.removeEventListener("abort", abort); reject(reason); },
      });
      this.#socket.send(JSON.stringify({
        jsonrpc: "2.0",
        id,
        method,
        params: { ...params, deadline_ms: deadlineMs },
      }), (error) => {
        if (!error) return;
        const pending = this.#pending.get(id);
        if (!pending) return;
        clearTimeout(pending.timer);
        this.#pending.delete(id);
        pending.reject(error);
      });
    });
  }

  async close(): Promise<void> {
    if (this.#closed) return;
    this.#closed = true;
    this.#disconnected(new BridgeRpcError("internal_error", "bridge closed"));
    if (this.#socket.readyState === WebSocket.CLOSED) return;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => { this.#socket.terminate(); resolve(); }, 1_000);
      this.#socket.once("close", () => { clearTimeout(timer); resolve(); });
      this.#socket.close(1000, "sidecar shutdown");
    });
  }

  #message(data: WebSocket.RawData, binary: boolean): void {
    if (binary) return this.#disconnected(new Error("binary bridge frames are not supported"));
    let parsed: unknown;
    try { parsed = JSON.parse(data.toString()); }
    catch { return this.#disconnected(new Error("bridge sent invalid JSON")); }
    const event = BridgeEventSchema.safeParse(parsed);
    if (event.success) {
      this.emit("event", event.data.method, event.data.params);
      this.emit(event.data.method, event.data.params);
      return;
    }
    const success = BridgeSuccessSchema.safeParse(parsed);
    if (success.success) {
      const pending = this.#pending.get(success.data.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.#pending.delete(success.data.id);
      pending.resolve(success.data.result);
      return;
    }
    const failure = BridgeFailureSchema.safeParse(parsed);
    if (failure.success) {
      const pending = this.#pending.get(failure.data.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.#pending.delete(failure.data.id);
      pending.reject(new BridgeRpcError(failure.data.error.code, failure.data.error.message, failure.data.error.data as Record<string, unknown>));
      return;
    }
    this.#disconnected(new Error("bridge sent an invalid protocol envelope"));
  }

  #sendNotificationCancel(operationId: string): void {
    if (this.#socket.readyState !== WebSocket.OPEN) return;
    const id = String(this.#nextId++);
    this.#socket.send(JSON.stringify({
      jsonrpc: "2.0", id, method: "system.cancel",
      params: { operation_id: operationId, deadline_ms: 2_000 },
    }));
  }

  #disconnected(error: unknown): void {
    if (!this.#closed) this.#closed = true;
    for (const pending of this.#pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.#pending.clear();
    this.emit("disconnect", error);
  }
}
