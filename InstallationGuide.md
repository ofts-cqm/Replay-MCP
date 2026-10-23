# Installation Guide

Nothing is published yet, so installation is manual.

### 1. Clone and install dependencies

```sh
git clone https://github.com/ofts-cqm/Replay-MCP.git replay-mcp
cd replay-mcp
npm ci
```

### 2. Build the sidecar and plugin

```sh
npm run build
npm run package:plugin
```

This compiles the Node.js MCP sidecar and creates the complete, installable
Codex/portable plugin at `plugins/replay-director/` and Claude Code plugin at
`plugins/replay-director-claude/`. Both directories are generated and ignored;
the authored manifests and skills live in `plugins/replay-director-template/`.

### 3. Build the Fabric mod

On Linux or macOS:

```sh
./gradlew build
```

The normal remapped mod JAR is created in `build/libs/`. Do not install the
sources JAR.

### 4. Install it in Minecraft

Create a dedicated Minecraft instance using a compatible version.
Copy the built Replay MCP JAR from ./build/libs into that instance’s `mods/` 
directory alongside compatible Replay Mod and Fabric API JARs.

Launch Minecraft once. Replay MCP writes local discovery data beneath the game
directory’s `.replay-mcp/` folder. 

### 5. Configure and start the sidecar

Persist the Minecraft game directory once:

```sh
node ./plugins/replay-director/bin/replay-mcp-server.mjs configure \
  --game-dir "/absolute/path/to/minecraft-game-directory"
```

Or launch a development sidecar with explicit paths:

```sh
node ./packages/mcp-server/dist/cli.js \
  --data-dir ./replay-mcp-data \
  --game-dir "/absolute/path/to/minecraft-game-directory"
```

`--data-dir` holds project, job, artifact, log, and discovery data.

### 6. Connect an MCP client

Configure your local MCP client to run the sidecar. The exact format differs by
client; the generic shape is:

```json
{
  "mcpServers": {
    "replay-mcp": {
      "command": "/absolute/path/to/node",
      "args": [
        "/absolute/path/to/replay-mcp/plugins/replay-director/bin/replay-mcp-server.mjs",
        "--game-dir",
        "/absolute/path/to/minecraft-game-directory"
      ]
    }
  }
}
```

### 7. Install the Codex plugin

```sh
codex plugin marketplace add "/absolute/path/to/replay-mcp"
codex plugin add replay-director@replay-mcp-local
```

Start a new Codex thread after installing or updating the plugin.

### 8. Install the Claude Code plugin

Choose a local load:

```sh
claude --plugin-dir "/absolute/path/to/replay-mcp/plugins/replay-director-claude"
```

Or register the built local checkout as a marketplace:

```sh
claude plugin validate "/absolute/path/to/replay-mcp/plugins/replay-director-claude"
claude plugin marketplace add "/absolute/path/to/replay-mcp"
claude plugin install replay-director@replay-mcp-local
```

Build before installation. The marketplace points to the generated Claude
folder; adding the unbuilt Git repository by URL is not a supported distribution
route. Start a fresh Claude Code session after updates. The adapter uses one
root `.mcp.json` and `${CLAUDE_PLUGIN_ROOT}` to resolve the pinned bundle,
independently of the user's working directory. This is for Claude Code, not
Claude web or other Claude products. Optional `agents/openai.yaml` metadata does
not affect Claude instructions.

### 9. Host capabilities and configuration

See [HOST_SUPPORT.md](HOST_SUPPORT.md) for tested versions and separate package,
connection, skill-loading, and production evidence. Generic MCP clients need
local stdio, complete JSON tool results, image inspection (inline or through a
local viewer), and access to verified artifact paths. If skills are not loaded
automatically, manually load the desired `skills/<name>/SKILL.md` and its linked
references from the generated plugin.

Use absolute paths for both the Node executable and pinned bundle in generic
client configurations. Node 20+ is required. Persistent state defaults to the
platform user-data directory, outside plugins. Override with an absolute
`REPLAY_MCP_DATA_DIR` or `--data-dir`; never put state in a generated/cache folder.
The bundle's location does not identify the production workspace. Set
`REPLAY_MCP_WORKSPACE` explicitly for workspace configuration when the host uses
a different working directory. Game discovery precedence is:

1. Repeated `--game-dir` arguments.
2. `REPLAY_MCP_GAME_DIR` (platform path-delimited).
3. `.replay-mcp.json` in the explicit/discovered workspace.
4. A prepared workspace `run/.replay-mcp` directory.
5. Persisted `configure` directories in the selected data directory.
6. Conventional game-directory guesses.

Use absolute game-directory overrides for reproducible launches. Workspace
configuration paths are relative to the workspace. Relative CLI/environment
paths resolve from the process working directory. Editors and music services
remain independently configured optional dependencies.

### 10. Optional: install a post-production editor

This product does not bundle any video editors. A recommended video editor is
[SynthCut](https://github.com/Relo-video/SynthCut). Please install a
post-production video editor so the AI agent can add captions, music, and cuts
after the video is made using Replay. 

### 11. Optional: configure a music generator

This product does not come with music. If you have a specific song you want to
use, you can download the song and tell the AI to use it. If you don't have a
specifical song to use, you can connect the agent to a music generator. 
For example, [ElevenLabs](https://elevenlabs.io/) has a greate music generation
API. 
