import { mkdtemp, mkdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";

const pluginRoot = resolve(process.argv[2] ?? resolve(dirname(fileURLToPath(import.meta.url)), "../../../plugins/replay-director"));
const manifest = JSON.parse(await readFile(resolve(pluginRoot, ".mcp.json"), "utf8"));
const launch = manifest.mcpServers?.["replay-mcp"];
if (!launch) throw new Error("replay-mcp launch definition is missing");
const plugin = JSON.parse(await readFile(resolve(pluginRoot, ".claude-plugin/plugin.json"), "utf8").catch(error => {
  if (error.code !== "ENOENT") throw error;
  return readFile(resolve(pluginRoot, "plugin.json"), "utf8");
}));
const sdk = JSON.parse(await readFile(new URL("../../../node_modules/@modelcontextprotocol/client/package.json", import.meta.url), "utf8"));
const dataRoot = await mkdtemp(resolve(tmpdir(), "replay mcp smoke "));
const environment = Object.fromEntries(Object.entries(process.env).filter((entry) => entry[1] !== undefined));
try {
  await mkdir(resolve(dataRoot, "unrelated cwd"));
  for (const mode of ["default", "legacy", "auto"]) {
    const transport = new StdioClientTransport({
      command: launch.command,
      args: launch.args.map(arg => arg.includes("${CLAUDE_PLUGIN_ROOT}") ? arg.replaceAll("${CLAUDE_PLUGIN_ROOT}", pluginRoot) : arg.startsWith("./") ? resolve(pluginRoot, arg) : arg),
      cwd: resolve(dataRoot, "unrelated cwd"),
      env: { ...environment, REPLAY_MCP_DATA_DIR: resolve(dataRoot, mode), REPLAY_MCP_WORKSPACE: dataRoot, REPLAY_MCP_GAME_DIR: resolve(dataRoot, "absent game") },
      stderr: "pipe",
    });
    const client = new Client({ name: "replay-plugin-smoke", version: "1.0.0" }, mode === "default" ? {} : { versionNegotiation: { mode, probe: { timeoutMs: 2000 } } });
    let stderr = "";
    transport.stderr?.on("data", chunk => { stderr += chunk.toString(); });
    try {
      await client.connect(transport);
      const tools = await client.listTools();
      if (tools.tools.length !== 48) throw new Error(`expected 48 tools, received ${tools.tools.length}`);
      const status = await client.callTool({ name: "system_status", arguments: {} });
      if (status.isError || status.structuredContent?.state !== "offline") throw new Error(`unexpected offline status: ${JSON.stringify(status.structuredContent)}`);
      process.stdout.write(`${JSON.stringify({ plugin_root: pluginRoot, plugin: plugin.version, sdk: sdk.version, node: process.version, mode, protocol: client.getNegotiatedProtocolVersion(), sidecar: status.structuredContent.sidecar_version, bridge: status.structuredContent.bridge_protocol, mod: "absent (offline)", tools: tools.tools.length, state: "offline" })}\n`);
    } catch (error) {
      if (stderr) process.stderr.write(stderr);
      throw error;
    } finally { await client.close(); }
  }
} finally { await rm(dataRoot, { recursive: true, force: true }); }
