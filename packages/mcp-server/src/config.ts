import { homedir } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { mkdir, stat } from "node:fs/promises";
import { readJson, writeJsonAtomic } from "./persistence.js";

export const DEVELOPMENT_GAME_DIR_ENV = "REPLAY_MCP_GAME_DIR";

export interface CliOptions {
  dataDir: string;
  gameDirs: string[];
  guessedGameDirs: string[];
  gameDirSources?: Record<string, string>;
  configFiles?: string[];
  discoveryIntervalMs: number;
  command: "serve" | "configure";
}

interface PersistedConfig {
  storage_version: 1;
  game_dirs: string[];
}

interface RepositoryConfig { game_dirs?: string[]; game_dir?: string }

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
  const persistedPath = resolve(dataDir, "config.json");
  const persisted = await readJson<PersistedConfig>(persistedPath, { storage_version: 1, game_dirs: [] });
  const fromEnvironment = (env[DEVELOPMENT_GAME_DIR_ENV] ?? "").split(delimiter).filter(Boolean).map((item) => resolve(item));
  const workspace = env.REPLAY_MCP_WORKSPACE ?? (env === process.env ? await findReplayWorkspace(process.cwd()) : undefined) ?? env.PWD;
  const repositoryConfigPath = workspace ? resolve(workspace, ".replay-mcp.json") : undefined;
  const repositoryConfig = repositoryConfigPath ? await readJson<RepositoryConfig>(repositoryConfigPath, {}) : {};
  const fromRepository = [repositoryConfig.game_dir, ...(repositoryConfig.game_dirs ?? [])].filter((item): item is string => typeof item === "string" && item.length > 0)
    .map((item) => resolve(workspace!, item));
  const workspaceRun = workspace ? resolve(workspace, "run") : undefined;
  const fromWorkspaceRun = workspaceRun && await isReplayGameDir(workspaceRun) ? [workspaceRun] : [];
  const guesses = conventionalGameDirs(platform, env).map((item) => resolve(item));
  const selected = explicit.length > 0 ? explicit
    : fromEnvironment.length > 0 ? fromEnvironment
      : fromRepository.length > 0 ? fromRepository
        : fromWorkspaceRun.length > 0 ? fromWorkspaceRun
          : persisted.game_dirs.length > 0 ? persisted.game_dirs.map((item) => resolve(item))
            : guesses;
  const source = explicit.length ? "cli" : fromEnvironment.length ? "environment" : fromRepository.length ? "repository_config"
    : fromWorkspaceRun.length ? "workspace_run" : persisted.game_dirs.length ? "persisted_config" : "conventional_guess";
  return {
    dataDir,
    gameDirs: [...new Set(selected)],
    guessedGameDirs: source === "conventional_guess" ? guesses : [],
    gameDirSources: Object.fromEntries([...new Set(selected)].map((item) => [item, source])),
    configFiles: [persistedPath, ...(repositoryConfigPath ? [repositoryConfigPath] : [])],
    discoveryIntervalMs,
    command,
  };
}

async function isReplayGameDir(path: string): Promise<boolean> {
  try { return (await stat(join(path, ".replay-mcp"))).isDirectory(); }
  catch { return false; }
}

async function findReplayWorkspace(start: string): Promise<string | undefined> {
  let current = resolve(start);
  while (true) {
    if (await isReplayGameDir(join(current, "run"))) return current;
    try { if ((await stat(join(current, ".replay-mcp.json"))).isFile()) return current; } catch { /* keep walking */ }
    const parent = resolve(current, "..");
    if (parent === current) return undefined;
    current = parent;
  }
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
