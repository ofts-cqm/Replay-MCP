import { mkdtemp, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";
import { describe, expect, it } from "vitest";

describe("portable and Codex plugin packaging", () => {
  it("keeps portable and compatibility launch definitions equivalent", async () => {
    const plugin = resolve(import.meta.dirname, "../../../plugins/replay-director");
    const portable = JSON.parse(await readFile(resolve(plugin, "mcp.json"), "utf8")) as Record<string, unknown>;
    const compatibility = JSON.parse(await readFile(resolve(plugin, ".mcp.json"), "utf8")) as Record<string, unknown>;
    expect(portable.mcpServers).toEqual(compatibility.mcpServers);
    expect(portable.mcpServers).toEqual({
      "replay-mcp": {
        type: "stdio",
        command: "node",
        args: ["./bin/replay-mcp-server.mjs"],
        cwd: ".",
      },
    });
    const portableManifest = JSON.parse(await readFile(resolve(plugin, "plugin.json"), "utf8")) as Record<string, unknown>;
    const compatibilityManifest = JSON.parse(await readFile(resolve(plugin, ".codex-plugin/plugin.json"), "utf8")) as Record<string, unknown>;
    expect(portableManifest).toMatchObject({ name: "replay-director", mcpServers: "./mcp.json" });
    expect(compatibilityManifest).toMatchObject({ name: "replay-director", mcpServers: "./.mcp.json" });
    expect(portableManifest.version).toBe(compatibilityManifest.version);
    expect(String(portableManifest.version)).toMatch(/^0\.1\.0\+codex\.\d{14}$/);
  });

  it("ships a directly runnable pinned sidecar bundle", async () => {
    const bundle = resolve(import.meta.dirname, "../../../plugins/replay-director/bin/replay-mcp-server.mjs");
    const source = await readFile(bundle, "utf8");
    expect(source.startsWith("#!/usr/bin/env node\n")).toBe(true);
    expect(source).not.toContain("npx ");
    const result = await run(process.execPath, [bundle, "--help"]);
    expect(result.code).toBe(0);
    expect(result.stderr).toContain("Usage: replay-mcp-server");
    expect(result.stdout).toBe("");
  });

  it("starts the bundled sidecar through the exact plugin stdio definition", async () => {
    const plugin = resolve(import.meta.dirname, "../../../plugins/replay-director");
    const manifest = JSON.parse(await readFile(resolve(plugin, ".mcp.json"), "utf8")) as {
      mcpServers: Record<string, { command: string; args: string[]; cwd: string }>;
    };
    const launch = manifest.mcpServers["replay-mcp"]!;
    const dataRoot = await mkdtemp(resolve(tmpdir(), "replay-mcp-stdio-"));
    const transport = new StdioClientTransport({
      command: launch.command,
      args: launch.args,
      cwd: resolve(plugin, launch.cwd),
      env: { ...stringEnvironment(process.env), REPLAY_MCP_DATA_DIR: resolve(dataRoot, "data") },
      stderr: "pipe",
    });
    const client = new Client({ name: "plugin-startup-smoke", version: "1.0.0" }, { versionNegotiation: { mode: "legacy" } });
    let stderr = "";
    transport.stderr?.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
    try {
      await client.connect(transport);
      const tools = await client.listTools();
      expect(tools.tools, stderr).toHaveLength(39);
      const status = await client.callTool({ name: "system_status", arguments: {} });
      expect(status.isError, stderr).not.toBe(true);
      expect(status.structuredContent).toMatchObject({ state: "offline" });
    } finally {
      await client.close().catch(() => undefined);
    }
  }, 15_000);
});

function stringEnvironment(environment: NodeJS.ProcessEnv): Record<string, string> {
  return Object.fromEntries(Object.entries(environment).filter((entry): entry is [string, string] => entry[1] !== undefined));
}

async function run(command: string, args: string[]): Promise<{ code: number | null; stdout: string; stderr: string }> {
  return await new Promise((resolveResult, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "";
    child.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
    child.once("error", reject);
    child.once("close", (code) => resolveResult({ code, stdout, stderr }));
  });
}
