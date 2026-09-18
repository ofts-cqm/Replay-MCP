#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/server/stdio";
import { writeSync } from "node:fs";
import { HELP, persistGameDirs, resolveOptions } from "./config.js";
import { buildServer } from "./server.js";

async function main(): Promise<void> {
  let options;
  try { options = await resolveOptions(process.argv.slice(2)); }
  catch (error) {
    if (error instanceof Error && error.message === "help_requested") {
      await writeStderr(`${HELP}\n`);
      return;
    }
    throw error;
  }
  if (options.command === "configure") {
    const explicitlyConfigured = process.argv.slice(2).includes("--game-dir");
    if (!explicitlyConfigured) throw new Error("configure requires at least one --game-dir");
    await persistGameDirs(options.dataDir, options.gameDirs);
    await writeStderr(`Saved ${options.gameDirs.length} Minecraft game director${options.gameDirs.length === 1 ? "y" : "ies"}.\n`);
    return;
  }

  const built = await buildServer(options);
  const transport = new StdioServerTransport();
  const keepAlive = setInterval(() => undefined, 60_000);
  let shuttingDown = false;
  let finishShutdown!: () => void;
  const shutdownFinished = new Promise<void>((resolve) => { finishShutdown = resolve; });
  const shutdown = async (reason: string): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    await writeStderr(`Replay MCP sidecar shutting down (${reason}).\n`);
    try { await built.close(); }
    catch (error) { await writeStderr(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`); }
    finally { clearInterval(keepAlive); finishShutdown(); }
  };
  process.once("SIGINT", () => { void shutdown("SIGINT").finally(() => process.exit(0)); });
  process.once("SIGTERM", () => { void shutdown("SIGTERM").finally(() => process.exit(0)); });
  process.stdin.once("end", () => { void shutdown("stdin closed"); });
  transport.onerror = (error) => process.stderr.write(`MCP transport error: ${error.message}\n`);
  transport.onclose = () => { void shutdown("transport closed"); };
  await built.server.connect(transport);
  process.stdin.resume();
  await shutdownFinished;
}

await main().catch(async (error) => {
  await writeStderr(`Replay MCP sidecar failed: ${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});

async function writeStderr(value: string): Promise<void> {
  writeSync(process.stderr.fd, value);
}
