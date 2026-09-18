#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/server/stdio";
import { HELP, persistGameDirs, resolveOptions } from "./config.js";
import { buildServer } from "./server.js";

async function main(): Promise<void> {
  let options;
  try { options = await resolveOptions(process.argv.slice(2)); }
  catch (error) {
    if (error instanceof Error && error.message === "help_requested") {
      process.stderr.write(`${HELP}\n`);
      return;
    }
    throw error;
  }
  if (options.command === "configure") {
    const explicitlyConfigured = process.argv.slice(2).includes("--game-dir");
    if (!explicitlyConfigured) throw new Error("configure requires at least one --game-dir");
    await persistGameDirs(options.dataDir, options.gameDirs);
    process.stderr.write(`Saved ${options.gameDirs.length} Minecraft game director${options.gameDirs.length === 1 ? "y" : "ies"}.\n`);
    return;
  }

  const built = await buildServer(options);
  const transport = new StdioServerTransport();
  let shuttingDown = false;
  const shutdown = async (reason: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    process.stderr.write(`Replay MCP sidecar shutting down (${reason}).\n`);
    try { await built.close(); }
    catch (error) { process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`); }
  };
  process.once("SIGINT", () => { void shutdown("SIGINT").finally(() => process.exit(0)); });
  process.once("SIGTERM", () => { void shutdown("SIGTERM").finally(() => process.exit(0)); });
  process.stdin.once("end", () => { void shutdown("stdin closed"); });
  transport.onerror = (error) => process.stderr.write(`MCP transport error: ${error.message}\n`);
  transport.onclose = () => { void shutdown("transport closed"); };
  await built.server.connect(transport);
}

main().catch((error) => {
  process.stderr.write(`Replay MCP sidecar failed: ${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});
