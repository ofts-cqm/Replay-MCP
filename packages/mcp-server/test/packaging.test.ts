import { mkdtemp, readFile, readdir, cp, rm, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { tmpdir } from "node:os";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";
import { Ajv2020 } from "ajv/dist/2020.js";
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
    expect(String(portableManifest.version)).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it("copies shared sources, protocol, and host adapters exactly with a single Claude launch", async () => {
    const root = resolve(import.meta.dirname, "../../..");
    const template = resolve(root, "plugins/replay-director-template");
    const codex = resolve(root, "plugins/replay-director");
    const claude = resolve(root, "plugins/replay-director-claude");
    for (const name of await readdir(template)) {
      if (name === "adapters") continue;
      await expectTreeEqual(resolve(template, name), resolve(codex, name));
      if (!["plugin.json", "mcp.json", ".mcp.json", ".codex-plugin"].includes(name)) {
        await expectTreeEqual(resolve(template, name), resolve(claude, name));
      }
    }
    for (const name of await readdir(resolve(template, "adapters/claude"))) {
      await expectTreeEqual(resolve(template, "adapters/claude", name), resolve(claude, name));
    }
    for (const plugin of [codex, claude]) await expectTreeEqual(resolve(root, "protocol/bridge-v1"), resolve(plugin, "protocol/bridge-v1"));
    await expectTreeEqual(resolve(codex, "bin"), resolve(claude, "bin"));
    const manifest = JSON.parse(await readFile(resolve(claude, ".claude-plugin/plugin.json"), "utf8"));
    expect(manifest).toMatchObject({ name: "replay-director", version: "0.2.0", author: { name: "OFTS CQM" }, skills: "./skills/" });
    expect(manifest).not.toHaveProperty("mcpServers");
    expect(await readdir(claude)).not.toContain("mcp.json");
    expect(await readdir(claude)).not.toContain(".codex-plugin");
    const launch = JSON.parse(await readFile(resolve(claude, ".mcp.json"), "utf8"));
    expect(launch.mcpServers).toEqual({ "replay-mcp": { type: "stdio", command: "node", args: ["${CLAUDE_PLUGIN_ROOT}/bin/replay-mcp-server.mjs"] } });
  });

  for (const host of ["codex", "claude"] as const) for (const mode of ["default", "legacy", "auto"] as const) {
    it(`launches ${host}/${mode} from an unrelated directory with spaces and portable schemas`, async () => {
      const root = await mkdtemp(resolve(tmpdir(), "replay compatibility "));
      const source = resolve(import.meta.dirname, `../../../plugins/replay-director${host === "claude" ? "-claude" : ""}`);
      const plugin = resolve(root, "installed plugin");
      await cp(source, plugin, { recursive: true });
      await mkdir(resolve(root, "unrelated cwd"));
      const launch = JSON.parse(await readFile(resolve(plugin, ".mcp.json"), "utf8")).mcpServers["replay-mcp"];
      const client = new Client({ name: "compatibility-test", version: "1" }, mode === "default" ? {} : { versionNegotiation: { mode, probe: { timeoutMs: 1000 } } });
      const transport = new StdioClientTransport({
        command: process.env.REPLAY_MCP_TEST_NODE ?? process.execPath,
        args: launch.args.map((arg: string) => host === "claude" ? arg.replaceAll("${CLAUDE_PLUGIN_ROOT}", plugin) : resolve(plugin, arg)),
        cwd: resolve(root, "unrelated cwd"),
        env: { ...stringEnvironment(process.env), REPLAY_MCP_DATA_DIR: resolve(root, "state"), REPLAY_MCP_WORKSPACE: root, REPLAY_MCP_GAME_DIR: resolve(root, "absent game") },
        stderr: "pipe",
      });
      try {
        await client.connect(transport);
        expect(client.getNegotiatedProtocolVersion()).toBe("2025-11-25");
        const { tools } = await client.listTools();
        expect(tools).toHaveLength(48);
        const ajv = new Ajv2020({ strict: false, validateFormats: false });
        for (const tool of tools) {
          expect(tool.inputSchema.type, tool.name).toBe("object");
          for (const key of ["anyOf", "oneOf", "allOf"]) expect(tool.inputSchema, tool.name).not.toHaveProperty(key);
          expect(ajv.validateSchema(tool.inputSchema), tool.name).toBe(true);
          ajv.compile(tool.inputSchema);
        }
        const status = await client.callTool({ name: "system_status", arguments: {} });
        expect(status.structuredContent).toMatchObject({ state: "offline", searched_game_dirs: [resolve(root, "absent game")] });
        expect(status.content[1]).toMatchObject({ type: "text", text: JSON.stringify(status.structuredContent) });
        const resource = await client.readResource({ uri: "replay-mcp://schema/spatial-map" });
        expect(resource.contents[0]).toHaveProperty("text");
      } finally { await client.close(); await rm(root, { recursive: true, force: true }); }
    }, 15_000);
  }

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
      env: { ...stringEnvironment(process.env), REPLAY_MCP_DATA_DIR: resolve(dataRoot, "data"), REPLAY_MCP_GAME_DIR: resolve(dataRoot, "empty-game") },
      stderr: "pipe",
    });
    const client = new Client({ name: "plugin-startup-smoke", version: "1.0.0" }, { versionNegotiation: { mode: "legacy" } });
    let stderr = "";
    transport.stderr?.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });
    try {
      await client.connect(transport);
      const tools = await client.listTools();
      expect(tools.tools, stderr).toHaveLength(48);
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

async function expectTreeEqual(source: string, target: string): Promise<void> {
  const { stat } = await import("node:fs/promises");
  if ((await stat(source)).isDirectory()) {
    expect((await readdir(target)).sort()).toEqual((await readdir(source)).sort());
    for (const name of await readdir(source)) await expectTreeEqual(resolve(source, name), resolve(target, name));
  } else expect((await readFile(target)).equals(await readFile(source)), target).toBe(true);
}
