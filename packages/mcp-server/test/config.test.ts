import { mkdtemp, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { describe, expect, it } from "vitest";
import { defaultDataDir, DEVELOPMENT_GAME_DIR_ENV, persistGameDirs, resolveOptions } from "../src/config.js";

describe("configuration precedence", () => {
  it("orders CLI, development environment, persisted configuration, then guesses", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-config-"));
    const dataDir = join(root, "data");
    await persistGameDirs(dataDir, [join(root, "persisted")]);
    const env = { [DEVELOPMENT_GAME_DIR_ENV]: join(root, "environment") } as NodeJS.ProcessEnv;
    expect((await resolveOptions(["--data-dir", dataDir, "--game-dir", join(root, "cli")], env)).gameDirs).toEqual([join(root, "cli")]);
    expect((await resolveOptions(["--data-dir", dataDir], env)).gameDirs).toEqual([join(root, "environment")]);
    expect((await resolveOptions(["--data-dir", dataDir], {})).gameDirs).toEqual([join(root, "persisted")]);
    const empty = join(root, "empty");
    const guessed = await resolveOptions(["--data-dir", empty], {}, "linux");
    expect(guessed.gameDirs).toEqual(guessed.guessedGameDirs);
    expect(guessed.guessedGameDirs[0]).toMatch(/\.minecraft$/);
  });

  it("rejects unknown flags and unsafe scan intervals", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-config-"));
    await expect(resolveOptions(["--data-dir", root, "--wat"])).rejects.toThrow("unknown option");
    await expect(resolveOptions(["--data-dir", root, "--discovery-interval-ms", "10"])).rejects.toThrow("at least 250ms");
  });

  it("uses a stable platform user-data directory unless explicitly overridden", async () => {
    const root = await mkdtemp(join(tmpdir(), "replay-mcp-config-"));
    const linuxEnvironment = { HOME: root, XDG_DATA_HOME: join(root, "xdg") } as NodeJS.ProcessEnv;
    expect(defaultDataDir("linux", linuxEnvironment)).toBe(join(root, "xdg", "replay-mcp"));
    expect((await resolveOptions([], linuxEnvironment, "linux")).dataDir).toBe(join(root, "xdg", "replay-mcp"));

    const explicit = join(root, "explicit");
    expect((await resolveOptions([], { ...linuxEnvironment, REPLAY_MCP_DATA_DIR: explicit }, "linux")).dataDir).toBe(explicit);
    expect(defaultDataDir("darwin", { HOME: root })).toBe(join(root, "Library", "Application Support", "Replay MCP"));
  });
});
