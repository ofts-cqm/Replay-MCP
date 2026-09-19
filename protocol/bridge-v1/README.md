# Replay MCP bridge v1 contract

This directory is the language-neutral contract between the Fabric bridge and
the Node sidecar. The wire protocol name is `replay-mcp.bridge/1`.

The bridge uses JSON-RPC 2.0-style text messages over an authenticated loopback
WebSocket. Requests always have string IDs. Responses contain exactly one of
`result` or `error`. Notifications have a method and params but no ID. Binary
files are represented by an `artifact` descriptor and are never put directly
on the JSON channel.

Schemas use JSON Schema draft 7 so both Java fixture tests and the TypeScript
Ajv suite can validate them. `fixtures/valid.json` and `fixtures/invalid.json`
are shared golden examples. Changes that are not backward-compatible require a
new versioned directory.

## Ground navigation action

Ground pathfinding is an additive `action.start_batch` action and is exposed
publicly through `game_perform`; it does not add another MCP tool or bridge
command family.

```json
{
  "kind": "navigate_to",
  "x": 12.5,
  "y": 64,
  "z": -8.5,
  "tolerance": 1.0,
  "sprint": false,
  "timeout_ms": 30000
}
```

`x`, `y`, and `z` are required finite coordinates. `tolerance` is optional,
defaults to `1.0`, and must be from `0.25` through `4.0`; `sprint` defaults to
`false`. The mod advertises support under `capabilities.navigation`, including
the native engine, loaded-chunk restriction, maximum distance, and unsupported
travel modes.

The current implementation is ground-only and uses Minecraft's native walking
path evaluator to plan within already-loaded chunks. It executes the route
through normal player inputs and never teleports, loads chunks, opens or breaks
blocks, swims, flies, or drives vehicles. Already-open doors may be traversed;
closed doors remain blocked and are never opened. Consecutive `navigate_to` or
`move` actions in the same batch hand off without an intermediate input release
or arrival-settle pause. Route failures use the existing
`conflict` error with a machine-readable `data.reason`; timeouts and
cancellation retain their existing error codes.
