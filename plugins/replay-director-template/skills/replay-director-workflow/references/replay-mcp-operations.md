# Replay MCP operations for deployed agents

This reference is self-contained because a deployed agent may have the plugin
but not the Replay MCP repository or architecture document.

For exact preset fields, contract examples, batch-review semantics, evidence and
restart procedures, read [Production, camera, and recovery details](production-camera-recovery.md)
when performing those operations. These details describe shipped behavior, not
unimplemented architecture proposals.

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
| Post-production | Assemble rendered shot plates into the deliverable | `production_edit` / `production_assemble` / `production_check`, or an external editor for unsupported effects |

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
- `replay_timeline_apply`, `replay_camera_preset`, `replay_path_clearance`,
  `replay_preview`, and `replay_validate_range`;
- `production_contract_draft` and `production_check` when publishing the report;
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

## Deterministic production completion (source implementation v1)

For a final-film request, create the project and call `production_contract_draft`
with the original request and frame-based requirements. The contract tool must
automatically present the physical player review window for that project; do not
ask the player to press F10 or navigate menus as the normal approval workflow.
Opening the window is an agent action. Commenting, locking, and overriding remain
physical player actions; never simulate their clicks or keys.

The automatic contract popup is **Review video plan**. It shows plain-language
Plan, Changes, Your request, and Comments pages. **Submit** saves the typed
comment for the current video and closes without approval. Automatic review shows
only the requested project's current contract. **Approve plan** approves that
displayed revision, saves its typed comment, then closes. Other projects are
excluded. Manual review lists only pending plans; when multiple plans are listed,
**Approve all videos (N)** atomically approves that displayed batch and **Next video**
browses it without dismissing other members. The list and revisions are fixed
when the window opens; a changed revision rejects the whole approval. Approved
contracts and their history remain saved. Typing alone is temporary; Escape discards
unsent input. Neither submission nor approval is inferred from window closure. It contains no export-override action. Export results and
player exceptions belong to the separate **Review export results** screen,
automatically opened by `production_check` for a failed/incomplete assembled
export (Replay MCP Controls is only a manual fallback). Never direct the player to approve a plan
as a substitute for accepting an export exception, or vice versa.

Treat draft persistence, review-window presentation, and player approval as three
separate states. Inspect the tool's presentation result and `production_status`
before telling the player the contract is ready on screen (`presentation` in the
draft result, `authority.presentation` in status). The presentation
state must be `visible` for the expected `project_id` and `draft_hash`
(the contract may be another video in the open batch, not the current page); `pending`,
`closed`, `blocked`, or `not_requested` is not evidence of a displayed window.
If presentation is pending behind an active operation, wait for the reported safe
presentation state;
do not claim the window is open. If presentation is blocked or unsupported by the
running mod, report the actual integration/version problem rather than asking the
player to find a window that was never displayed. F10 is a manual fallback, not a
required step for a contract proposal.

Verify the selected instance and its actual screen when presentation fails. A
saved draft does not prove a replay was opened, and opening a replay does not
prove review was displayed. Contract review itself need not require a world;
for a recorded-clip production, separately identify the finalized source and open
its immutable working copy before claiming replay editing or rendering has begun.

The draft tool waits for physical submission by default (`wait_timeout_ms: 50000`;
use `0` only when an immediate return is needed for diagnostics). Tell the player
before the call that Submit sends comments and Approve plan saves approval and
closes. Keep the agent turn active while awaiting review; do not finish with
“message me when done.” No extra chat message is needed while the tool is waiting.

Read `review_wait.status`. On `timeout`, continue through `production_status`
with `wait_timeout_ms: 50000`, `draft_hash`, and `after_submission_sequence` copied
from `review_wait`. The status response puts these fields under `authority`.
This is a bounded wait in the sidecar; do not rapidly poll or re-draft to wait.
The durable sequence catches a submission made between calls or across reconnects.
On `commented`, read the submitted comments, revise against `base_hash`, and
present the new proposal. On `approved` or `already_approved`, verify the exact
`locked_hash` before continuing. If approval includes comments, read them too;
a change to the approved contract still requires a newly reviewed revision.
On `closed`, no submission is implied: read fresh authority before diagnosing
a dismissal, since a physical decision may have arrived afterward. A matching
locked contract remains valid even after the window closes or Minecraft restarts.
Do not reopen automatically. On `blocked`,
`unsupported`, `superseded`, cancellation, or disconnect, report/resolve the actual
state; never interpret it as approval. A new tool wait can resume observation,
but cannot wake a session whose agent turn has already ended.

Review temporarily rejects conflicting mutations; wait for it to close without
reacquiring a still-valid lease. Read-only waits do not extend director ownership
indefinitely or restore revoked control. Proceed with contract-dependent work
only after the matching revision is physically locked. Incidental mouse input
and review typing must not revoke control; if they do, report the defect rather
than repeatedly reacquiring.

Default runtime tolerance is ±20%, preferred shots 3–10 seconds, hard shots 1–15
seconds, no shot-count limit, and no footage reuse. Incorporate explicit player
requirements in the reviewed contract. Unimplemented measurable requirements go
in `mechanical_requirements` and remain INCOMPLETE; subjective composition belongs
in `subjective_criteria` and is outside the mechanical guarantee.

Use `replay_camera_preset` for shared static/slide/rise/push/pan/orbit/follow
baking, or keep `replay_timeline_apply` for manual paths. Presets replace camera
and replay-time tracks against an expected revision. Duration and replay-time mode
are explicit. Interior defaults resolve one native support surface +1.6 blocks;
`interior_height_mode: explicit` retains the supplied height. This is not a stair
following floor-height solver. Follow uses player-eye-relative elevation (default
+1.5), seeded rear/side variation, and horizontal 3–5-block defaults. Stops retain
heading and teleport intervals fail. `follow-candidate-planner/1` searches stable
rear-side/distance/elevation candidates, validates every connecting segment, runs
eight constrained smoothing passes, and revalidates the actual native path.
Supply project_id/shot_id from the persistent shot plan to retain generation,
seed, provenance, geometry and blocked-interval checkpoints independently.

Call `replay_path_clearance` for the entire actual native path. Policy `native-linear-frozen-sweep/3` certifies
only frozen replay time with linear interpolation and loaded static block collision
shapes, sweeping a half-block clearance box. Advancing linear 1x paths use
`native-linear-packet-static-sweep/1`: a bounded full packet scan must establish
unchanged geometry. Unknown packets, block/chunk changes, moving shapes and
exhausted budgets return unverified. This is supported advancing static geometry,
not general simulation of changing/moving obstacles.

Follow additionally traces the actual native renderer at a bound `follow_fps`
(20/40/60/80/100/120, default 60), checks joined 50 ms eye interpolation envelopes,
and binds `native-follow-tick-envelope/1` to player/source/session/path/FPS.
Source start must exceed 1s; duration is 50ms-aligned with a 50ms source tail.
Preroll/tail history must also be supported. Final rendering with a different FPS
or native range is rejected until regenerated. Render a complete checked plate
and trim its frames in controlled assembly. Source `replay_end_us`, when supplied, must equal
start+duration. Trace scratch frames are not visual review or final media.
Pose changes, teleports, missing players, unsupported tick phase or incomplete
history fail closed. Visibility means a collision-shape-free conservative eye-ray
hull; it does not prove full-body composition or visible noncollision geometry.
A skip is recorded as skipped, never safe, and cannot satisfy `require_collision`.
Sampled `replay_validate_range` remains a preview diagnostic, not whole-path proof.
The sweep checks a neighboring block shell for protruding vanilla shapes such as
fences, and fails closed for unloaded chunks, modded blocks, moving pistons,
uncovered timeline ranges and nonlinear time mappings. Its block budget is 100,000.
It reports the blocked segment and earliest conservative contact position/time.
Confirm the client advertises policy `/3`; earlier receipts cannot satisfy required
collision evidence because their client chunk-presence guard could falsely pass.
After upgrading, recheck and rerender affected safety-required plates. The
half-block bound includes the near plane only for standard unstabilized rendering,
FOV <=110 and aspect <=4; entities and visible non-collision geometry are excluded.
Evidence binds immutable replay hash, working-copy session, native timeline hash
(including source-time mapping), checked output range and projection policy. Render
receipts reuse it only inside that envelope. Recheck after edits/reopen/settings
changes; manual paths use the identical check. Do not reuse stale evidence.

Basic preset generator version `camera-presets/3` preserves requested duration.
Push/pull defaults to yaw/pitch direction, negative distance pulls/falls, pan plus
fixed aim is rejected, and orbit orientation comes from center/aim. Interior orbit
support is resolved at its actual starting XZ, using start.y as the search ceiling.
Explicit height and direction are creative inputs; do not silently replace them.
When a checked preset is blocked/unverified, normal rollback restores the prior
revision. If lease loss prevents rollback, read state and stop at human_override;
never reacquire to finish a rollback without explicit recovery authorization.

Final plates must come from completed high-quality native render jobs. Submit job
IDs and integer-frame trims to `production_edit`, then `production_assemble`.
Version 1 supports straight cuts only, without audio, overlays, transitions, retiming
or non-footage padding. Those requirements remain unsupported, not silently omitted.
External editor exports have no trusted assembly receipt and cannot be called PASS.

Run `production_check` against the actual final artifact. PASS covers only supported
mechanical rules. FAIL preserves known violations; INCOMPLETE preserves missing
or unsupported evidence. Repair ordinary failures without requesting an exception.
Assembly has a persisted limit of eight attempts and three identical consecutive
failures; exhaustion never waives a rule. Only the player can override the exact
export and findings using **Accept exceptions** in the separate
**Review export results** screen; the result is PLAYER_OVERRIDDEN,
not PASS. A contract popup does not imply an override was presented or approved.
There is no mandatory human footage-review gate. Automated visual review and actual
rendered-frame inspection remain useful for composition and revisions.

After restart/compaction, read `production_progress` (works without a live client),
`production_status`, `project_get`, and job state. Persisted summaries are computed
from shot-plan hashes, generation/geometry checkpoints, current artifact hashes,
completed native media receipts and job owners. Pass project_id/shot_id on presets,
previews, range validation and renders. Changes to one shot invalidate its evidence;
finished unrelated media is retained. A new observer sidecar does not interrupt a
live owner's jobs. A proven-dead owner leaves failed/interrupted jobs, never finished
partial artifacts. Assembly ownership is persisted, and an edit changed during an
export cannot acquire its receipt. No in-place renderer resume is promised.

After inspecting actual completed preview/video artifacts, use
`production_visual_review` to retain accepted/revise observations. These are agent
visual notes bound to media hashes, distinct from geometry and final certification;
no human footage gate is introduced. Handoffs include the computed recovery state.
The summary's historical completion/assembly is not a current passing check.
Cached completion is historical: re-run `production_check` before delivery. Handoffs
retain the brief and last observed production authority; structural project validation
alone never certifies a final film. Native-render lineage currently supports at most
8,000 frames per plate and excludes spectator paths; missing lineage is incomplete.

### Failed-export review presentation

`production_check` publishes the deterministic report and automatically opens
**Review export results** for an assembled export with unresolved failures.
Inspect `export_presentation` in the tool result, or `authority.export_presentation`
in `production_status`: require `visible` for the expected project and report
`binding` before claiming the screen is open. It is separate from contract
`presentation`. Pending means an active operation or player review is in the way;
status reads never reopen dismissed windows. An explicit new check re-presents
the current failed export. PASS and already accepted exceptions do not prompt.
Without an assembled artifact, repair the missing evidence first: there is no
finished export to review. `unsupported` means the running mod needs updating.
Do not direct the player to F10 as the normal failed-export flow. Opening the
screen is not acceptance; only the physical player can accept exceptions for
that exact export. Never simulate that button or rewrite authority files.


### Delayed export decisions and AFK review

`production_check` waits for the physical export decision by default. **Accept
exceptions** records a binding-specific exception and closes; **Reject and revise**
records rejection and closes; **Later** or Escape only defers. Never infer
rejection from dismissal or a timeout. On `export_review_wait.status: timeout`,
keep the turn active and continue through `production_status` with
`wait_timeout_ms: 50000`, `export_binding` from the returned `binding`, and
`after_decision_sequence`. This wait is read-only and does not reopen the UI or
require a lease. Release unused director control during long review waits; do not
renew or reacquire control merely to observe the decision.

A delayed decision is durable, including decisions made between wait calls or
while disconnected. `accepted` requires the matching saved override; `rejected`
means repair within the approved contract, then revalidate; `deferred` means stop
waiting without interpreting a decision. Changing the export supersedes the old
binding. Do not end a turn claiming you will resume automatically: this wait can
resume an active agent, but a finished/closed Codex session needs host-level event
integration or another user message. Do not claim that integration exists.

Artifact/project/job stores now merge unrelated records under a cross-process
write lock and reject stale same-record writes. Refresh after a record conflict;
never restore stale whole index snapshots. An abandoned lock fails closed with a
bounded timeout; stop all writers before investigating/removing that lock.
Recover missing artifact entries only from completed native render descriptors
whose files still pass the original size/checksum checks. Never fabricate receipts.


### Sampled preview image source

Frame/contact-sheet previews use native Replay Mod still rendering at each exact
output time, including the authored endpoint. Check capability
`sampled_preview_renderer: replaymod-native-still/1`; reload older clients before
retrying. Width/height are honored (default 640x360). This is more expensive than
viewport screenshots, so begin with a small sample count or use a draft video.
`replay_validate_range` returns geometry diagnostics without images. Its `valid`
flag and `chunks_ready` describe geometry, never successful visual rendering.
Inspect actual preview images; game observation remains a viewport capture.

### Evidence binding after the phase 4/5 fixes

Finalized-source metadata/import reads the immutable ZIP and works while its
working copy is open; do not close the replay merely to import clips. Preview
jobs retain the supplied project/shot plan binding for visual notes. A plan/source
or output-settings change makes older progress evidence stale; regenerate only
affected shots. Old binding-version evidence is also stale after this upgrade.
Follow FPS/full-range requirements persist independently of collision rechecks
and replay reopening. Generic or skipped clearance cannot erase them. Regenerate
follow paths authored before this upgrade to establish the new durable binding.
