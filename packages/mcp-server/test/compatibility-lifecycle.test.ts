import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";
import { expect, it } from "vitest";
import { createFakeBridge } from "./fake-bridge.js";

for (const mode of ["default", "legacy", "auto"] as const) {
  it(`${mode}: cancellation, EOF, exclusive leases, media and durable review after reconnect`, async () => {
    const root = await mkdtemp(resolve(tmpdir(), "replay lifecycle "));
    const authority: Record<string, unknown> = {
      review_submission_version: 1, draft_hash: "draft-a", presentation: { state: "visible" },
      export_decision_version: 1, report: { binding: "export-a" }, export_presentation: { state: "visible" },
    };
    const fake = await createFakeBridge(resolve(root, "game"), { hangActions: true, productionState: authority });
    const clients: Client[] = [];
    async function connect(state: string) {
      const client = new Client({ name: "lifecycle-test", version: "1" }, mode === "default" ? {} : { versionNegotiation: { mode, probe: { timeoutMs: 1000 } } });
      clients.push(client);
      await client.connect(new StdioClientTransport({
        command: process.env.REPLAY_MCP_TEST_NODE ?? process.execPath,
        args: [resolve(import.meta.dirname, "../../../plugins/replay-director/bin/replay-mcp-server.mjs")],
        cwd: root,
        env: { ...Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined)), REPLAY_MCP_DATA_DIR: resolve(root, state), REPLAY_MCP_GAME_DIR: fake.gameDir, REPLAY_MCP_WORKSPACE: root },
        stderr: "pipe",
      }));
      expect(client.getNegotiatedProtocolVersion()).toBe("2025-11-25");
      return client;
    }
    async function call(client: Client, name: string, args: Record<string, unknown> = {}) {
      const result = await client.callTool({ name, arguments: args });
      expect(result.isError, JSON.stringify(result.structuredContent)).not.toBe(true);
      expect(result.content[1]).toMatchObject({ type: "text", text: JSON.stringify(result.structuredContent) });
      return result.structuredContent as Record<string, any>;
    }
    try {
      const first = await connect("first state");
      const second = await connect("second state");
      await call(first, "control_acquire");
      const busy = await second.callTool({ name: "control_acquire", arguments: {} });
      expect(busy.structuredContent).toMatchObject({ error: { code: "control_busy" } });
      expect(busy.content[1]).toMatchObject({ type: "text", text: JSON.stringify(busy.structuredContent) });
      const controller = new AbortController();
      const action = first.callTool({ name: "game_perform", arguments: { actions: [{ kind: "wait", ticks: 100 }] } }, { signal: controller.signal });
      const cancelled = expect(action).rejects.toThrow();
      await until(() => fake.calls.some(call => call.method === "action.start_batch"));
      controller.abort();
      await cancelled;
      await until(() => fake.calls.some(call => call.method === "system.cancel") && fake.calls.some(call => call.method === "lease.release"));
      await call(second, "control_acquire");
      const releases = fake.calls.filter(call => call.method === "lease.release").length;
      await second.close();
      await until(() => fake.calls.filter(call => call.method === "lease.release").length > releases);
      await call(first, "control_acquire");
      await call(first, "control_release");
      const observation = await first.callTool({ name: "game_observe", arguments: {} });
      const image = observation.content.find(block => block.type === "image");
      expect(image?.type).toBe("image");
      const artifact = (observation.structuredContent as any).artifact;
      expect((await first.readResource({ uri: artifact.resource_uri })).contents[0]).toMatchObject({ blob: image!.data });

      // A representative large project response crosses Claude's documented
      // 50k-character file-persistence boundary; the server must retain it all.
      const title = "production notes ".repeat(4000).trim();
      const { project } = await call(first, "project_create", { title, frame_rate: 30, resolution: { width: 1280, height: 720 }, output_root: "renders" });
      expect(project.title).toBe(title);
      const cursor = { project_id: project.id, wait_timeout_ms: 20, draft_hash: "draft-a", after_submission_sequence: 0 };
      const timedOut = await call(first, "production_status", cursor);
      expect(timedOut.authority.review_wait).toMatchObject({ status: "timeout", draft_hash: "draft-a", after_submission_sequence: 0 });
      const exportCursor = { project_id: project.id, wait_timeout_ms: 20, export_binding: "export-a", after_decision_sequence: 0 };
      expect((await call(first, "production_status", exportCursor)).authority.export_review_wait.status).toBe("timeout");
      await first.close();
      Object.assign(authority, {
        locked_hash: "draft-a", review_submission: { sequence: 1, draft_hash: "draft-a", approved: true },
        export_decision: { sequence: 1, binding: "export-a", decision: "accepted" }, override: { binding: "export-a" },
        presentation: { state: "closed" }, export_presentation: { state: "closed" },
      });
      const resumed = await connect("first state");
      expect((await call(resumed, "project_get", { project_id: project.id })).project.title).toBe(title);
      const before = fake.calls.length;
      expect((await call(resumed, "production_status", cursor)).authority.review_wait.status).toBe("approved");
      expect((await call(resumed, "production_status", exportCursor)).authority.export_review_wait.status).toBe("accepted");
      expect(fake.calls.slice(before).every(call => call.method === "production.status")).toBe(true);
    } finally {
      await Promise.allSettled(clients.map(client => client.close()));
      await fake.close();
      await rm(root, { recursive: true, force: true });
    }
  }, 20_000);
}

async function until(predicate: () => boolean) {
  const deadline = Date.now() + 5000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error("expected bridge event did not arrive");
    await new Promise(resolve => setTimeout(resolve, 20));
  }
}
