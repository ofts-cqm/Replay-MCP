# Local host testing

Build the generated plugin distribution before installing the repo marketplace:

```sh
npm run build
npm run package:plugin
codex plugin marketplace add "/home/ofts/Replay MCP"
codex plugin add replay-director@replay-mcp-local
```

Start a new Codex thread after reinstalling so the updated skill and tools are
loaded. The generated plugin launches only the bundled `bin/replay-mcp-server.mjs` using
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

## Claude Code and generic stdio

Use the same stable `major.minor.patch` plugin version in all three template
manifests, with a version bump when distributing changed content. Sidecar,
bridge and Fabric mod versions are independent.

Both distributions are produced by `npm run package:plugin`. Claude adapter
sources live under `plugins/replay-director-template/adapters/claude/`; never
edit generated distributions or installed caches as the sole change.

```sh
claude plugin validate --strict ./plugins/replay-director-claude
claude plugin validate --strict ./.claude-plugin/marketplace.json
claude --plugin-dir "/absolute/path/to/repo/plugins/replay-director-claude"
# Alternative local marketplace installation:
claude plugin marketplace add "/absolute/path/to/repo"
claude plugin install replay-director@replay-mcp-local
```

The Claude package has one auto-discovered `.mcp.json` and no manifest-declared
server entry. `${CLAUDE_PLUGIN_ROOT}` resolves its bundle without a plugin-root
working directory. Codex's existing portable/compatibility launch files remain
equivalent host entry points. Use only one host adapter per installation.

Generic clients launch an absolute Node executable with an absolute bundled
`bin/replay-mcp-server.mjs` argument. Set `REPLAY_MCP_WORKSPACE` for the user's
workspace and `REPLAY_MCP_GAME_DIR` for explicit Minecraft discovery. Manually
load SKILL.md and references if the client has no skill loader. Keep data outside
generated/installed plugin directories.

Run these offline checks from the repository:

```sh
npm run typecheck
npm test
node packages/mcp-server/scripts/smoke-plugin.mjs
node packages/mcp-server/scripts/smoke-plugin.mjs ./plugins/replay-director-claude
```

The smoke uses temporary state, an absent game directory, an unrelated working
directory and all three SDK negotiation modes. It prints the actual negotiated
protocol, SDK, Node, plugin, sidecar, and bridge versions. The full tests also
check schemas, matching JSON text/results, verified media/resources, exclusive
leases, cancellation/shutdown, and durable review after restarting stdio.

For host acceptance, use a temporary Claude profile (`CLAUDE_CONFIG_DIR`) and
explicit isolated discovery first. Verify local loading and marketplace install,
all five skills and linked references, exactly one server, system_status, tool
calls, inline image inspection, and cursor-based review continuation. Verify
installed shared files, adapter files, protocol, bundle, and version against the
generated distribution after installation. Repeat with the supported Codex
version. A validator or SDK client alone does not establish these host workflows.

Only mark live production verified after capture/import, camera, rendering,
artifact inspection and completion checks run in that host with the intended
Minecraft/mod versions. Record results in the repository's HOST_SUPPORT.md.
See the shared `host-capabilities.md` reference for sequential delegation fallback
and recovery when a host truncates or saves a large response to disk.
