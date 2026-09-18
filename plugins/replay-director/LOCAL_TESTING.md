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

For a development client in a workspace `./run` directory, set
`REPLAY_MCP_WORKSPACE` to the workspace root or launch from that workspace; a
prepared `run/.replay-mcp` directory is discovered automatically. A repository
may also provide `.replay-mcp.json`:

```json
{ "game_dirs": ["run"] }
```

For any other non-default game directory, configure discovery with:

```sh
node ./plugins/replay-director/bin/replay-mcp-server.mjs configure \
  --game-dir "/absolute/path/to/minecraft/game-directory"
```

The command persists the selected directories in the sidecar data directory;
running sidecars hot-reload repository and persisted directory configuration.
For a non-persistent development override, set `REPLAY_MCP_GAME_DIR` to the
absolute game directory before launching the sidecar.

Discovery never starts Minecraft. Before launching a development client, check
`system_status`, its descriptor diagnostics, and the existing process for the
same game directory. Do not start a second client merely because discovery is
misconfigured; doing so can truncate the shared development log.

`npm run test:unit` is sandbox-safe. `npm run test:integration` binds an
authenticated temporary server on `127.0.0.1`; an `EPERM` result in a
restricted sandbox is an environment skip, not a product failure. A permitted
localhost run is required before release.
