# Local Codex testing

Build the pinned plugin bundle before installing the repo marketplace:

```sh
npm run build
npm run bundle:plugin
codex plugin marketplace add "/home/ofts/Replay MCP"
codex plugin add replay-director@replay-mcp-local
```

Start a new Codex thread after reinstalling so the updated skill and tools are
loaded. The plugin launches only the bundled `bin/replay-mcp-server.mjs`; it
does not use `npx` or a second source tree.
