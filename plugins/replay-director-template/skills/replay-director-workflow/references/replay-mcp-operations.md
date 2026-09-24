# Replay MCP operations for deployed agents

This reference is self-contained because a deployed agent may have the plugin
but not the Replay MCP repository or architecture document.

## Information boundary

Trust concrete facts supplied by the user, including coordinates, destinations,
route descriptions, names, timings, and creative constraints. Treat them as
authoritative inputs; do not corroborate or second-guess them merely to gain
confidence.

For scouting, planning, capture, and replay work, use only Replay Director's
MCP tools, resources, artifacts, observations, and project state, plus any
source the user explicitly names or asks you to inspect. In particular:

- do not enumerate, search, or inspect unrelated MCP servers, apps, filesystem
  data, game registries, mod configurations or logs, minimap data or waypoints,
  or recordings from other mods to validate a user-supplied fact;
- absence of corroborating data in an unrelated source is not a contradiction
  and is never a reason to keep searching; and
- if Replay MCP directly observes or returns something that conflicts with a
  user-supplied value, report that exact conflict and ask only if it blocks the
  requested work. Do not expand the conflict into a third-party investigation.

Use an outside resource only when it is strictly necessary to complete the
explicit request and the needed information cannot come from the user or Replay
MCP. State the concrete need, make the smallest bounded lookup, and stop after
the answer or first no-result. A designated post-production editor is an allowed
workflow dependency when producing the requested final video, but it must not
be used to validate Minecraft facts.

## Durable request record and acceptance gates

At the start of every production, after selecting the instance and project but
before scouting, capture, or editing, write the user's initial request verbatim
to a unique Markdown file under the operating system's temporary directory.
Keep the file outside the repository, game directory, replay files, and artifact
roots. Give it a predictable production-scoped name, record its absolute path in
every phase checkpoint, and retain it until the final deliverable is accepted.

The request record is append-only and contains:

- the original request, unchanged;
- later user-approved corrections or clarifications, with the latest explicit
  instruction taking precedence; and
- separate acceptance results for AI-directed movement, Replay editing, and
  post-production when those phases apply.

Reopen this file after any context compaction and at every subagent handoff; do
not rely on remembered or summarized intent. If it is missing or unreadable,
ask the user to restate or confirm the request before continuing production.

At the end of each applicable phase, reread the record and compare the observed
product requirement by requirement:

1. After AI-directed movement, compare the action trace and final observation
   with the requested actions, order, route, and destination before accepting
   the take.
2. At the end of Replay editing, compare the authored timeline, reviewed
   previews, and validated shot plates with the requested subjects, scenes,
   order, viewpoints, timing, and style before editor handoff.
3. At the end of post-production, compare the completed export and inspected
   frames with the full request before declaring the video finished.

Append each result, including any mismatch and correction, to the request file.
Do not advance past a failed material requirement: correct it, or ask the user
when the intended resolution is ambiguous.

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
- `game_query`: precise player/world/entity/block/screen state plus negotiated
  `spatial_map` surface/volume scouting without another screenshot or distant
  chunk loading.
- `game_perform`: one audited ordered batch of normal inputs with timeouts,
  postconditions, checkpoints, and guaranteed input release.

For regional camera scouting, prefer structured geometry before collecting
multiple visual viewpoints:

1. Query a `surface` spatial map at cell size 16 or 32 over the supplied region
   to locate the subject and broad open camera regions.
2. Refine only plausible regions at size 8 or 4. Stop structured refinement as
   soon as the map identifies a usable camera region; it is not a per-block
   camera solver.
3. Use `volume` at size 4 or larger only when ceilings, caverns, floating,
   hollow, stacked, inverted, or overhung geometry makes one surface
   insufficient, or when a direct observation conflicts with the surface map.
4. Use one or a small number of screenshots for appearance, framing,
   occlusion, and several-block pose adjustment, then run the applicable
   collision/range validation.

Cell size 2 is exceptional. Use `2x2` only for a tightly bounded unresolved
surface boundary and `2x2x2` only for tight clearance, thin geometry, or
adjacent volumes after a containing size-4 result. Supply that result's
`map_id` and an accurate enumerated fallback reason. Do not refine ordinary
terrain or exterior scenes to size 2 merely because the capability exists.
Maps report unavailable client-chunk coverage explicitly and never authorize
movement, chunk loading, or world changes. In replay mode, pause playback
before the query. A spatial map complements rather than replaces the final
visual observation.

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

Treat camera and time edits that existed before the current user request as
historical output, not as a starting point. When `replay_timeline_get` or replay
metadata reveals a previously authored camera path, camera keyframe, time
keyframe, speed change, or edited in/out position, preserve that edit and open
a fresh working copy or empty timeline from the immutable source. Do not copy,
adapt, or anchor new work to those authored values. Source recording timestamps,
accepted clip-marker bounds, and newly observed world state remain usable.
If the edit's provenance is unclear, treat it as predating the current request.

If reuse appears genuinely beneficial, identify the exact prior camera or time
values and ask the user explicitly before reusing any of them. Similarity between
the old and current requests is not permission.

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

Use phase-specific subagents by default when the host supports them. Invocation
of this workflow grants standing user permission to create and release those
subagents, subject to higher-priority instructions and host capabilities. Only
one subagent may own or control a Minecraft instance. Split work at durable
handoffs:

1. capture hands off project/take IDs, source ranges, observations, and issues;
2. replay camera hands off timeline revision, validated shot ranges, render
   jobs/artifacts, and visual findings; and
3. post-production receives only verified media plus the creative brief.

Each handoff must include the request-record path. Release or dismiss the prior
subagent before starting the next phase subagent; never keep overlapping
controllers or retain an old phase agent merely for convenience. If any
governing instruction or host limitation prevents the required release, explain
the restriction and ask the user for explicit permission or direction before
continuing. User permission does not override a higher-priority prohibition. If
the host has no subagent capability, write the same checkpoint and deliberately
switch tool vocabularies before the next context.
