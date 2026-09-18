import { homedir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { mkdir } from "node:fs/promises";
import { readJson, writeJsonAtomic } from "./persistence.js";

export const DEVELOPMENT_GAME_DIR_ENV = "REPLAY_MCP_GAME_DIR";

export interface CliOptions {
  dataDir: string;
  gameDirs: string[];
  guessedGameDirs: string[];
  discoveryIntervalMs: number;
  command: "serve" | "configure";
}

interface PersistedConfig {
  storage_version: 1;
  game_dirs: string[];
}

function valueAfter(args: string[], index: number, option: string): string {
  const value = args[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`${option} requires a value`);
  return value;
}

export async function resolveOptions(
  argv: string[],
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
): Promise<CliOptions> {
  let command: "serve" | "configure" = "serve";
  let dataDir = resolve(env.REPLAY_MCP_DATA_DIR ?? defaultDataDir(platform, env));
  const explicit: string[] = [];
  let discoveryIntervalMs = 2_000;
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index]!;
    if (arg === "configure") command = "configure";
    else if (arg === "--data-dir") dataDir = resolve(valueAfter(argv, index++, arg));
    else if (arg === "--game-dir") explicit.push(resolve(valueAfter(argv, index++, arg)));
    else if (arg === "--discovery-interval-ms") {
      discoveryIntervalMs = Number(valueAfter(argv, index++, arg));
      if (!Number.isInteger(discoveryIntervalMs) || discoveryIntervalMs < 250) throw new Error("discovery interval must be at least 250ms");
    } else if (arg === "--help" || arg === "-h") {
      throw new Error("help_requested");
    } else if (arg.startsWith("--")) throw new Error(`unknown option: ${arg}`);
  }

  await mkdir(dataDir, { recursive: true });
  const persisted = await readJson<PersistedConfig>(resolve(dataDir, "config.json"), { storage_version: 1, game_dirs: [] });
  const fromEnvironment = (env[DEVELOPMENT_GAME_DIR_ENV] ?? "").split(delimiter).filter(Boolean).map((item) => resolve(item));
  const guesses = conventionalGameDirs(platform, env).map((item) => resolve(item));
  const selected = explicit.length > 0 ? explicit
    : fromEnvironment.length > 0 ? fromEnvironment
      : persisted.game_dirs.length > 0 ? persisted.game_dirs.map((item) => resolve(item))
        : guesses;
  return {
    dataDir,
    gameDirs: [...new Set(selected)],
    guessedGameDirs: explicit.length || fromEnvironment.length || persisted.game_dirs.length ? [] : guesses,
    discoveryIntervalMs,
    command,
  };
}

export function defaultDataDir(platform: NodeJS.Platform, env: NodeJS.ProcessEnv): string {
  const home = env.HOME ?? env.USERPROFILE ?? homedir();
  if (platform === "win32") {
    return join(env.LOCALAPPDATA ?? env.APPDATA ?? join(home, "AppData", "Local"), "Replay MCP");
  }
  if (platform === "darwin") return join(home, "Library", "Application Support", "Replay MCP");
  return join(env.XDG_DATA_HOME ?? join(home, ".local", "share"), "replay-mcp");
}

export async function persistGameDirs(dataDir: string, gameDirs: string[]): Promise<void> {
  await writeJsonAtomic(resolve(dataDir, "config.json"), {
    storage_version: 1,
    game_dirs: [...new Set(gameDirs.map((item) => resolve(item)))],
  } satisfies PersistedConfig);
}

export function conventionalGameDirs(platform: NodeJS.Platform, env: NodeJS.ProcessEnv): string[] {
  if (platform === "win32") {
    const appData = env.APPDATA;
    return appData ? [`${appData}\\.minecraft`] : [];
  }
  if (platform === "darwin") return [`${homedir()}/Library/Application Support/minecraft`];
  return [`${homedir()}/.minecraft`];
}

export const HELP = `Usage: replay-mcp-server [configure] [options]\n\n` +
  `  --data-dir <path>              Persistent sidecar data directory (defaults to the platform user-data directory)\n` +
  `  --game-dir <path>              Minecraft game directory (repeatable)\n` +
  `  --discovery-interval-ms <ms>   Rescan interval (minimum 250)\n` +
  `\nDevelopment environment: ${DEVELOPMENT_GAME_DIR_ENV}`;
