# Local Codex testing

Build the pinned plugin bundle before installing the repo marketplace:

```sh
npm run build
npm run bundle:plugin
codex plugin marketplace add "/home/ofts/Replay MCP"
codex plugin add replay-director@replay-mcp-local
```

Start a new Codex thread after reinstalling so the updated skill and tools are
loaded. The plugin launches only the bundled `bin/replay-mcp-server.mjs` using
paths relative to the installed plugin root; it does not depend on host-side
plugin variable expansion, `npx`, or a second source tree. Sidecar state uses
the platform user-data directory by default and can be overridden with
`REPLAY_MCP_DATA_DIR` or `--data-dir`.

For a development client whose game directory is not the platform default
(for example, this repository's `./run` directory), configure discovery once
before starting a new Codex thread:

```sh
node ./plugins/replay-director/bin/replay-mcp-server.mjs configure \
  --game-dir "/absolute/path/to/minecraft/game-directory"
```

The command persists the selected directories in the sidecar data directory.
An already-running sidecar does not hot-reload this setting, so start a new
Codex thread or otherwise restart the sidecar after changing it. For a
non-persistent development override, set `REPLAY_MCP_GAME_DIR` to the absolute
game directory before launching the sidecar.
