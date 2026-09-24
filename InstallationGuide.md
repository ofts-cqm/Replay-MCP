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
plugin at `plugins/replay-director/`. That directory is generated and ignored;
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
      "command": "node",
      "args": [
        "/absolute/path/to/replay-mcp/packages/mcp-server/dist/cli.js",
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

### 8. Optional: install a post-production editor

This product does not bundle any video editors. A recommended video editor is
[SynthCut](https://github.com/Relo-video/SynthCut). Please install a
post-production video editor so the AI agent can add captions, music, and cuts
after the video is made using Replay. 

### 9. Optionl: configure a music generator

This product does not come with music. If you have a specific song you want to
use, you can download the song and tell the AI to use it. If you don't have a
specifical song to use, you can connect the agent to a music generator. 
For example, [ElevenLabs](https://elevenlabs.io/) has a greate music generation
API. 
