import { mkdtemp } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { afterEach, describe, expect, it } from "vitest";
import { buildServer, type BuiltReplayMcpServer } from "../src/server.js";
import { createFakeBridge, type FakeBridge } from "./fake-bridge.js";

describe("job-aware director lease", () => {
  let built: BuiltReplayMcpServer | undefined;
  let fake: FakeBridge | undefined;
  afterEach(async () => { await built?.close(); await fake?.close(); });

  it("marks heartbeats active while an authorized bridge job is running", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-lease-job-"));
    const gameDir = join(root, "game");
    fake = await createFakeBridge(gameDir);
    built = await buildServer({ dataDir: join(root, "data"), gameDirs: [gameDir], guessedGameDirs: [], discoveryIntervalMs: 60_000, command: "serve" });
    const client = built.runtime.client();
    await built.runtime.leases.acquire(client, "test");
    const job = await built.runtime.jobs.create("analysis", { instance_id: fake.instanceId });
    await built.runtime.jobs.update(job.id, { status: "running" });
    await new Promise((resolve) => setTimeout(resolve, 1_100));
    const heartbeat = fake.calls.filter((call) => call.method === "lease.heartbeat").at(-1);
    expect(heartbeat?.params.active).toBe(true);
    await built.runtime.jobs.update(job.id, { status: "completed" });
  });
});
