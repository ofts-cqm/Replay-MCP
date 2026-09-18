import { McpServer } from "@modelcontextprotocol/server";
import type { CliOptions } from "./config.js";
import { registerResources } from "./resources/register.js";
import { ReplayMcpRuntime } from "./runtime.js";
import { registerTools } from "./tools/register.js";
import { SIDECAR_VERSION } from "./types.js";

export interface BuiltReplayMcpServer {
  server: McpServer;
  runtime: ReplayMcpRuntime;
  close(): Promise<void>;
}

export async function buildServer(options: CliOptions): Promise<BuiltReplayMcpServer> {
  const runtime = new ReplayMcpRuntime(options);
  await runtime.start();
  await runtime.audit.append("sidecar.started", { session_id: runtime.sessionId, game_dirs: options.gameDirs });
  const server = new McpServer({ name: "replay-mcp-server", version: SIDECAR_VERSION }, {
    instructions: "Call system_status first. Acquire control before mutations. Use observe-act-verify loops, treat recording takes as logical until finalization, visually verify previews, and always release control.",
  });
  registerTools(server, runtime);
  registerResources(server, runtime);
  let closed = false;
  return {
    server,
    runtime,
    async close() {
      if (closed) return;
      closed = true;
      await runtime.close();
      await server.close();
    },
  };
}
