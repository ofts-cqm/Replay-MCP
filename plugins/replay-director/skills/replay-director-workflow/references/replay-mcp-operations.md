# Replay MCP operations for deployed agents

This reference is self-contained because a deployed agent may have the plugin
but not the Replay MCP repository or architecture document.

## Mental model

Replay MCP has three different control contexts. Never mix their actions:

| Context | Purpose | Primary tools |
| --- | --- | --- |
| Live game | Observe or operate the current player and mark a logical take | `game_*`, `recording_*` |
| Replay | Open an immutable recording copy, control playback, author cameras, preview, validate, render | `replay_*`, `render_*` |
| Post-production | Assemble rendered shot plates into the deliverable | a separately installed editor MCP |

`game_perform` changes live player inputs. `replay_playback` changes the replay
viewer. Timeline camera keyframes change only the replay camera. None of these
is a substitute for another.

The editor-neutral project hierarchy is:

```text
Project -> Scene -> Take (replay + source range) -> Shot -> Render artifact
```

Unless the user explicitly requests multiple videos, one request creates one
project and one final video. Actions introduced with “then” are normally scene
or shot order within that video.

## Director control lifecycle

The Minecraft mod enforces one exclusive fenced lease per instance. Reads do
not require it. Sidecar project changes use project revisions rather than this
lease.

Call `control_acquire` once, immediately before the first mutation in one
contiguous live-game or replay phase. After success:

- keep using the lease; do not acquire again before every action, seek,
  timeline edit, preview, or render;
- do not send heartbeats—the sidecar renews automatically, including during
  sidecar-owned preview, finalization, and render jobs;
- `system_status` and `control_status` report `sidecar_owns_control` when this
  MCP session owns the lease; and
- a repeated acquire by the same sidecar is idempotent, but it is still an
  unnecessary call.

Release once after the last lease-required operation and bridge-backed job.
Release before a long editor-only phase. Acquire once again only if a later,
new Replay MCP mutation phase is genuinely needed.

Never acquire during human player-led live capture. `project_import_replay`
also remains lease-free.

A busy lease belongs to another controller and must not be stolen. Physical
player override, disconnect, `control_required`, `stale_fence`, or confirmed
lease loss terminates the mutation phase. Inspect status and report the stop;
do not loop on `control_acquire` or replay a mutation automatically.

### What requires the lease

- `game_perform`;
- `recording_start`, `recording_stop`, `recording_finalize_and_open`, and
  `recording_add_marker`;
- `replay_open`, `replay_close`, `replay_save`, and `replay_playback`;
- `replay_timeline_apply`, `replay_preview`, and `replay_validate_range`;
- `render_start`, `render_still`, and cancellation of bridge-backed jobs.

Observations, queries, status, job reads, artifact reads, replay metadata,
timeline reads, render preflight, project reads, project revision operations,
replay import, validation, and handoff export do not require the director
lease.

## Public tool map

### System, work, and evidence

- `system_status`: first call; reports discovery, instances, modes,
  capabilities, policies, ownership, and active jobs.
- `control_status`, `control_acquire`, `control_release`: lease lifecycle.
- `job_list`, `job_get`, `job_cancel`: persistent asynchronous work. A job ID
  means started, not completed.
- `artifact_list`: checksum-verified screenshots, replays, previews, renders,
  stills, manifests, and logs. Read linked `replay-mcp://artifact/...`
  resources for output evidence.

### Live game

- `game_observe`: a synchronized image and structured snapshot. Use `clean`
  for footage review and `annotated` when stable target IDs help selection.
- `game_observe_motion`: a bounded motion burst or contact sheet.
- `game_query`: precise player/world/entity/block/screen state without another
  screenshot or distant chunk loading.
- `game_perform`: one audited ordered batch of normal inputs with timeouts,
  postconditions, checkpoints, and guaranteed input release.

Ground `navigate_to` uses Minecraft-native evaluation, current loaded terrain,
and normal inputs. It does not open closed doors, swim, drive, fly, perform
parkour, load distant chunks, or authorize world interaction. Rehearse paths
before recording. The accepted agent-directed performance must use one complete
batch so MCP round trips do not introduce visible pauses.

### Recording and import

- `recording_status`: Replay Mod armed/logical-take state.
- `recording_start`, `recording_add_marker`, `recording_stop`: logical ranges
  inside a connection-scoped Replay Mod recording.
- `recording_finalize_and_open`: intentionally disconnect, wait for `.mcpr`
  finalization, register the immutable source, and open a working copy.
- `replay_list`, `replay_get`: finalized replay discovery and metadata.
- `project_import_replay`: lease-free normalization of versioned start/end or
  revoke markers into a `CapturedTake`.

`recording_stop` does not immediately create an independent replay. Never
invent a replay ID or edit the source file. Preserve pending finalization and
use the working copy returned after finalization/open.

### Replay camera and rendering

- `replay_open`, `replay_close`, `replay_save`: immutable-source session and
  save-as lifecycle.
- `replay_playback`: seek/play/pause/speed/step/spectate/detach in integer
  microseconds.
- `replay_observe`: current replay camera plus synchronized visual state.
- `replay_timeline_get`, `replay_timeline_apply`: revisioned camera/time/shot
  data. Re-read on conflict.
- `replay_preview`: internal low-cost review in authored output time.
- `replay_validate_range`: settled seeks plus chunk, collision, distance, and
  line-of-sight checks for a final shot range.
- `render_presets`, `render_validate`, `render_start`, `render_still`: render
  discovery, preflight, jobs, and exact-time stills.

Always use `replay_preview` contact sheets/frames or a `draft_360p` video for
internal review. Reserve final-quality rendering for an edit that has passed
that review. A final-quality `render_start` needs the successful matching
`replay_validate_range` job, unchanged timeline revision, and identical output
range. Poll jobs to terminal state and inspect actual rendered frames; raw
replay observations alone are not final acceptance evidence.

### Project and handoff

- `project_list`, `project_create`, `project_get`, `project_apply`: revisioned
  production intent, scene/take/shot order, captions, audio notes, and metadata.
- `project_validate`: reference, range, continuity, frame-rate, and handoff
  checks.
- `project_export_handoff`: versioned manifest with absolute paths and hashes
  for a post-production editor.

Keep unsupported titles, audio, grading, or other editorial concepts in the
project/editor instead of pretending Replay Mod has native tracks.

## Safety and creative defaults

- Do not modify or interact with the world without explicit permission. Ask if
  a route or requested scene appears to require it.
- Use 1:1 time by default. Building/scene replay shots may pause the world.
- Avoid walls and terrain during ordinary camera motion. Separate scenes into
  clips for the editor by default.
- A user-requested direct smooth transition may pass through geometry for
  0.5–1 second while moving directly between scene positions. Geometry passage
  elsewhere requires explicit permission.
- Preserve source `.mcpr` files and incomplete artifacts. Never present an
  incomplete or failed output as finished.

## Context handoff

If the host supports permitted subagents, only one may own/control a Minecraft
instance. Split work at durable handoffs:

1. capture hands off project/take IDs, source ranges, observations, and issues;
2. replay camera hands off timeline revision, validated shot ranges, render
   jobs/artifacts, and visual findings; and
3. post-production receives only verified media plus the creative brief.

Release or dismiss the prior subagent at each handoff. If working alone, write
the same checkpoint and deliberately switch tool vocabularies before the next
context.
