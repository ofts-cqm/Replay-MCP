import { mkdtemp, readFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { describe, expect, it } from "vitest";
import { BridgeClient, BridgeRpcError } from "../src/bridge/client.js";
import { InstanceDescriptorSchema } from "../src/types.js";
import { createFakeBridge } from "./fake-bridge.js";

describe("authenticated bridge client", () => {
  it("authenticates hello, correlates calls, times out, and fences a second sidecar", async () => {
    const gameDir = await mkdtemp(join(tmpdir(), "replay-mcp-bridge-"));
    const fake = await createFakeBridge(gameDir);
    const descriptor = InstanceDescriptorSchema.parse(JSON.parse(await readFile(join(gameDir, ".replay-mcp", "instances", fake.instanceId, "bridge.json"), "utf8")));
    const token = (await readFile(join(gameDir, ".replay-mcp", "instances", fake.instanceId, "token"), "utf8")).trim();
    const first = await BridgeClient.connect(descriptor, token);
    const second = await BridgeClient.connect(descriptor, token);
    expect(first.hello?.instance_id).toBe(fake.instanceId);
    const lease = await first.call("lease.acquire", { owner_label: "first" }) as Record<string, unknown>;
    expect(lease.fence).toBe(1);
    await expect(second.call("lease.acquire", { owner_label: "second" })).rejects.toMatchObject({ code: "control_busy" });
    await expect(first.call("test.timeout", {}, { deadlineMs: 20 })).rejects.toMatchObject({ code: "timeout" });
    await first.close();
    await second.close();
    await fake.close();
  });

  it("rejects the wrong protected token", async () => {
    const gameDir = await mkdtemp(join(tmpdir(), "replay-mcp-bridge-"));
    const fake = await createFakeBridge(gameDir);
    const descriptor = InstanceDescriptorSchema.parse(JSON.parse(await readFile(join(gameDir, ".replay-mcp", "instances", fake.instanceId, "bridge.json"), "utf8")));
    await expect(BridgeClient.connect(descriptor, "cd".repeat(32), 500)).rejects.toBeInstanceOf(Error);
    await fake.close();
  });
});
