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

## Spatial observation

`observation.spatial_map` is an additive read-only bridge method exposed
publicly as `game_query(kind: "spatial_map")`. Support and all hard limits are
negotiated through `capabilities.spatial_map`; an older mod is rejected by the
sidecar with `capability_unavailable` before an unknown bridge method is sent.

Requests use integer half-open bounds and `representation: "surface" |
"volume"`. Ordinary cell sizes are 4, 8, 16, and 32. Cell size 2 is a stricter
fallback requiring a recent containing size-4 `refines_map_id` and one of the
representation-specific enumerated reasons. Bounds expand outward on the
world-origin grid with mathematical floor alignment for negative coordinates.
Surface sampling still uses the requested Y band (reported separately as
`sampled_y_bounds`), while its returned effective bounds remain grid-aligned.

The mod reads already-loaded client chunks only. Default requests return
explicit partial coverage and absolute unavailable boxes;
`require_complete: true` returns `outside_loaded_area`. Results include a map
ID, instance/dimension/replay identity, requested and effective bounds, fixed
increasing Z/X/Y ordering, a response-wide block palette, capture tick range,
consistency, work/latency telemetry, and response byte count. Surface rows
contain height range/mean and surface material shares. Volume columns contain
absolute vertical occupancy runs and exposed-face material shares. The
language-neutral request/result/capability shapes and fixtures live in
`schemas/spatial-map.schema.json`.

The dedicated error codes are `query_too_large`, `outside_loaded_area`, and
`no_loaded_coverage`. Playing replays return `conflict` with
`data.reason: "replay_must_be_paused"`; loading and rendering return
`invalid_mode`. Spatial queries are lease-free and never create image
artifacts.
