import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";

const pluginRoot = resolve(process.argv[2] ?? resolve(import.meta.dirname, "../../../plugins/replay-director"));
const manifest = JSON.parse(await readFile(resolve(pluginRoot, ".mcp.json"), "utf8"));
const launch = manifest.mcpServers?.["replay-mcp"];
if (!launch) throw new Error("replay-mcp launch definition is missing");

const dataRoot = await mkdtemp(resolve(tmpdir(), "replay-mcp-installed-smoke-"));
const environment = Object.fromEntries(Object.entries(process.env).filter((entry) => entry[1] !== undefined));
const transport = new StdioClientTransport({
  command: launch.command,
  args: launch.args,
  cwd: resolve(pluginRoot, launch.cwd),
  env: { ...environment, REPLAY_MCP_DATA_DIR: resolve(dataRoot, "data") },
  stderr: "pipe",
});
const client = new Client({ name: "replay-plugin-smoke", version: "1.0.0" }, { versionNegotiation: { mode: "legacy" } });
let stderr = "";
transport.stderr?.on("data", (chunk) => { stderr += chunk.toString(); });

try {
  await client.connect(transport);
  const tools = await client.listTools();
  if (tools.tools.length !== 38) throw new Error(`expected 38 tools, received ${tools.tools.length}`);
  const status = await client.callTool({ name: "system_status", arguments: {} });
  if (status.isError || status.structuredContent?.state !== "offline") {
    throw new Error(`unexpected offline status: ${JSON.stringify(status.structuredContent)}`);
  }
  process.stdout.write(`Plugin stdio smoke passed: ${pluginRoot} (38 tools, offline status)\n`);
} catch (error) {
  if (stderr) process.stderr.write(stderr);
  throw error;
} finally {
  await client.close().catch(() => undefined);
}
