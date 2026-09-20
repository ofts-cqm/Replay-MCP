# Replay MCP

> A local Minecraft filmmaking bridge for recording real Replay Mod footage,
> editing it, rendering shots, and handing finished assets to an editor.

## Introduction

Replay MCP connects a Minecraft client, Replay Mod, a local MCP server, and a
Codex plugin. It lets an AI assistant work with a real Minecraft client through
safe, normal game inputs; record or import performances; edit Replay Mod
timelines; render footage; and organize a production project. A player
or an agent performs in a real world, Replay Mod records the session, and the
replay tools create the final camera work and render output.

The goal is to open a door for AI to create Minecraft Videos. Currently, it is still
significantly slower than human editing, but it opens up the possibility for AI
Minecraft film making. It also frees YouTubers from frequent video editing and
give them more time to focus on planning and script writing while the AI processes
all edits in the background.

Replay MCP supports two ways to capture a scene:

- **Player-led:** you perform the scene and use local keybindings to mark the
  parts you want to keep. An AI imports those accepted clips after recording.
- **Agent-directed:** an AI acquires control, observes the game, and uses normal
  Minecraft inputs and permitted commands to perform or direct a scene.

Both paths use the same downstream replay-editing, rendering, project, and
editor-handoff workflow.

The MCP server is local-first: it communicates with an MCP client over standard
input/output and with Minecraft over an authenticated loopback connection. It
is not a public Minecraft server API, a server-console bridge, or a general
code-execution service.

## Current support

The current development tree includes:

- A client-side Fabric mod for Minecraft `26.2`, Fabric Loader `0.19.5`,
  Fabric API `0.160.0+26.2`, and Replay Mod `26.2-2.6.27`.
- A local authenticated bridge between the Minecraft mod and Node.js MCP
  sidecar.
- Minecraft observation, normal-input game actions, and ground navigation.
- Replay Mod recording, replay opening, timeline editing, preview, still, and
  render operations.
- Player-led clip keys, clip-marker parsing, and replay import.
- A fenced director lease for agent-controlled mutations.
- Local projects, artifacts, jobs, validation, and editor-neutral handoffs.
- A 39-tool MCP server and bundled Replay Director Codex plugin.

Automated TypeScript, Java, protocol, package, and fake-bridge workflow checks
exist. They are not a substitute for live graphical acceptance in Minecraft.

## Planned work

Future or optional work includes editor-specific integrations, audio support, multi-client
coordination, richer dialogue/voice tracks, automated editorial analysis, more
timeline interchange formats, secured remote operation, standalone binaries,
and published packages.

The core intentionally excludes arbitrary shell/JVM execution, server-console
control, anti-cheat or permission bypass, silent world mutation, and a bundled
general-purpose video editor.

## Development disclaimer

**Replay MCP is still in development.** Nothing is published as a stable
release, compatibility may change, and parts of the user experience are still
rough. Expect breaking changes between development checkouts.

Use a disposable Minecraft instance and test world at first. Keep backups of
important worlds, recordings, and editor projects.

## Requirements

- Git.
- A JDK capable of Java 25 toolchains.
- Node.js 20 or newer and npm.
- A dedicated Minecraft/Fabric instance matching
  [`gradle.properties`](gradle.properties).
- The matching Replay Mod and Fabric API dependencies.
- An MCP-capable client, or Codex for the bundled plugin.

## Installation from source

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
npm run bundle:plugin
```

This compiles the Node.js MCP sidecar and creates the pinned plugin bundle at
`plugins/replay-director/bin/`.

### 3. Build the Fabric mod

On Linux or macOS:

```sh
./gradlew build
```

The normal remapped mod JAR is created in `build/libs/`. Do not install the
sources JAR.

### 4. Install it in Minecraft

Create a dedicated Minecraft instance using the versions in
[`gradle.properties`](gradle.properties). Copy the remapped Replay MCP JAR into
that instance’s `mods/` directory alongside compatible Replay Mod and Fabric
API JARs.

Launch Minecraft once. Replay MCP writes local discovery data beneath the game
directory’s `.replay-mcp/` folder. This is how the sidecar finds and
authenticates the running client. Do not copy its tokens into source control,
prompts, or plugin configuration.

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

Do not configure your MCP client to connect directly to the private bridge
WebSocket; it is an internal authenticated protocol.

### 7. Optional: install the Codex plugin

```sh
codex plugin marketplace add "/absolute/path/to/replay-mcp"
codex plugin add replay-director@replay-mcp-local
```

Start a new Codex thread after installing or updating the plugin.

## Usage guide

### Start a session

1. Start Minecraft with Replay Mod and Replay MCP installed.
2. Enter the world you want to record in and make sure Replay Mod is ready.
3. Start the sidecar, or let your configured MCP client start it.
4. Ask the AI to call `system_status` first. It reports whether Minecraft was
   discovered, its current mode, and the available capabilities.

### Player-led capture keys

When Replay Mod recording is armed, use these default local keybindings:

| Key | Action                  |
| --- |-------------------------|
| `F6` | Start a candidate clip. |
| `F7` | Accept the active clip. |
| `F8` | Cancel the active clip. |

The keys are remappable in Minecraft controls. Use `F6` at the beginning of a
take and `F7` at the end only when you want to keep it. Use `F8` to discard a
mistake. You can create several accepted or revoked attempts in one recording.

When you are done, leave the world so Replay Mod can finalize the `.mcpr`
recording. The accepted clips are imported later; player-led marking does not
give the AI control of your character.

`F10` controls the local overlay and `F12` is the default emergency-stop key.
Change these controls if they conflict with other bindings.

### Talk to the AI

With the Replay Director plugin installed, communicate in plain filmmaking
terms. Tell the AI what you want to capture, whether you or the AI should
perform it, and what result you want. Examples:

```text
I am going to perform the scene. Help me capture three takes, then import the
accepted clips and render a cinematic wide shot.
```

```text
Direct a short sunset walk through the village. Record a take, make a smooth
camera shot in Replay Mod, show me a preview, and render it when I approve.
```

```text
Check the Minecraft connection first. Do not take control until I ask you to.
```

For player-led capture, tell the AI that you will use the clip keys. It should
help you confirm readiness and import the finalized recording afterwards. It
should not acquire the director lease or send movement input during your live
performance.

For AI-directed work, ask it to acquire control only when you want it to act.
Only one director may mutate a Minecraft instance at once.

### What happens after capture

After an accepted replay is finalized, the AI can import it, edit and preview
it, render footage, and export an editor-neutral handoff. Ask it to inspect
rendered frames rather than treating a successful job message as visual approval.

### Hyprland background rendering

On Hyprland, a Replay Mod render may stop progressing when Minecraft is moved
to an inactive workspace, even when Minecraft's pause-on-lost-focus option is
disabled. This is compositor presentation behavior and is unrelated to the
Replay MCP director lease.

For Hyprland 0.56 using the Lua configuration provider, keep the Minecraft
window rendering while it is on another workspace and set the background FPS
limit to at least the intended output frame rate:

```lua
hl.config({
    misc = {
        render_unfocused_fps = 60,
    },
})

hl.window_rule({
    name = "minecraft-replay-background-render",
    match = {
        class = "^Minecraft\\* .*$",
        xwayland = true,
    },
    render_unfocused = true,
})
```


## High-level architecture

[`ARCHITECTURE.md`](ARCHITECTURE.md) is the detailed, authoritative contract.
At a high level, the components are arranged like this:

```text
Player or AI
       |
       | Minecraft keys / MCP over stdio
       v
Replay Director plugin or MCP client
       |
       v
Node.js MCP sidecar
  - tools, leases, jobs, projects, and artifacts
       |
       | authenticated local bridge
       v
Replay MCP Fabric mod in Minecraft
  - player inputs, observation, clip markers, Replay Mod adapters
       |
       v
Minecraft + Replay Mod
       |
       v
Source .mcpr files, previews, renders, and editor handoffs
```

The Fabric mod runs inside Minecraft and owns player inputs, Replay Mod access,
clip markers, and lease safety. The Node.js sidecar in
[`packages/mcp-server`](packages/mcp-server) exposes MCP tools, manages jobs
and projects, and discovers the game through the private authenticated bridge
([`protocol/bridge-v1`](protocol/bridge-v1)). The Replay Director plugin bundles
the sidecar and workflow guidance. Source `.mcpr` files remain separate from
derived previews, renders, checksums, and handoff manifests.

## Repository map

```text
.
├── ARCHITECTURE.md              Detailed contracts and status
├── src/                         Java Fabric mod and tests
├── protocol/bridge-v1/          Shared bridge schemas and fixtures
├── packages/mcp-server/         Node.js TypeScript MCP sidecar
├── plugins/replay-director/     Codex plugin and filmmaking workflows
├── build.gradle                 Fabric/Loom build configuration
└── package.json                 npm workspace root
```

## Validation notes

When changing a public tool, bridge schema, capability, safety boundary, or
workflow claim, update [`ARCHITECTURE.md`](ARCHITECTURE.md) too. A build or
fake-bridge test is not visual acceptance; changes that affect capture or
rendering need a real Minecraft test with inspected rendered output.

Useful checks include:

```sh
git diff --check
npm run typecheck
npm run test
./gradlew test
./gradlew build
```

## License

This project declares the MIT license. See [`LICENSE.txt`](LICENSE.txt) and
review the licenses for Minecraft, Fabric, Replay Mod, and dependencies before
redistributing a build.
