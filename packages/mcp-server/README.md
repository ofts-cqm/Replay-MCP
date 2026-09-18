# replay-mcp-server

Local Node 20+ MCP sidecar for Replay MCP. It discovers authenticated Fabric
bridge instances, enforces lease ownership at the public boundary, persists
jobs/projects/artifacts, and exposes the 36-tool surface documented in the
repository `ARCHITECTURE.md`.

```sh
replay-mcp-server --data-dir ./replay-mcp-data --game-dir ~/.minecraft
```

Standard output is reserved for MCP protocol messages. Diagnostics are written
to standard error and the data-directory audit log.
