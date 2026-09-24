# replay-mcp-server

Local Node 20+ MCP sidecar for Replay MCP. It discovers authenticated Fabric
bridge instances, enforces lease ownership at the public boundary, persists
jobs/projects/artifacts, and exposes the 39-tool surface documented in the
repository `ARCHITECTURE.md`.

Ground pathfinding is available as the `navigate_to` action inside
`game_perform`. It accepts a fixed coordinate, optional arrival tolerance, and
optional sprint flag; capability negotiation reports whether the connected mod
supports Minecraft-native ground planning.

Regional camera scouting is available through
`game_query({ kind: "spatial_map", ... })` when the connected mod advertises
`capabilities.spatial_map`. Surface and volume maps inspect loaded client chunks
without taking the director lease or capturing an image. Size-2 refinement is
intentionally gated by a recent containing size-4 result and an audited reason.

```sh
replay-mcp-server --data-dir ./replay-mcp-data --game-dir ~/.minecraft
```

Standard output is reserved for MCP protocol messages. Diagnostics are written
to standard error and the data-directory audit log.
