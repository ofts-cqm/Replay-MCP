import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { describe, expect, it } from "vitest";

describe("portable and Codex plugin packaging", () => {
  it("keeps portable and compatibility launch definitions equivalent", async () => {
    const plugin = resolve(import.meta.dirname, "../../../plugins/replay-director");
    const portable = JSON.parse(await readFile(resolve(plugin, "mcp.json"), "utf8")) as Record<string, unknown>;
    const compatibility = JSON.parse(await readFile(resolve(plugin, ".mcp.json"), "utf8")) as Record<string, unknown>;
    expect(portable.mcpServers).toEqual(compatibility.mcpServers);
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
});

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
