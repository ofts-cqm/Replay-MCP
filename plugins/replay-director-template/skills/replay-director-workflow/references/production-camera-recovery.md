# Production, camera, and recovery details

Read the applicable sections when drafting/revising a contract, generating camera
paths, assembling a film, or recovering interrupted work. The shared
[operations reference](replay-mcp-operations.md) defines control and information
boundaries. Public field names below match the current tool schemas.

## Contract fields and physical review

The agent drafts requirements; the player reviews plain-language Plan, Changes,
Your request, and Comments pages. `production_contract_draft` both saves and
requests presentation in one call. It needs director control; it does not open
a world or replay. F10 opens Replay MCP Controls as a manual fallback only.

Send `project_id`, `contract`, and `base_hash` (empty for the first draft; the
current draft hash for revisions). Required contract fields are
`original_request`, `fps`, `target_frames`, `width`, and `height`. Preserve the
original request exactly; append later requirements to the durable request record
and reflect their measurable consequences in the revised contract.

| Field | Default / meaning |
| --- | --- |
| `runtime_tolerance` | 0.2: target duration ±20% |
| `min_shot_frames`, `max_shot_frames` | 1 second and 15 seconds at contract FPS; hard bounds |
| `preferred_min_frames`, `preferred_max_frames` | 3 and 10 seconds; advisory pacing band |
| `min_shots`, `max_shots` | Omitted by default; do not invent a shot-count constraint |
| `reuse_budget_frames` | 0; increase only for player-requested reuse in the reviewed contract |
| `require_collision` | Schema defaults false; set true explicitly when verified whole-path safety is part of the brief |
| `mechanical_requirements` | Unsupported extra measurable rules; these remain INCOMPLETE, not assumed satisfied |
| `subjective_criteria` | Composition/style goals retained for visual review; not mechanically guaranteed |

For “every shot strictly 3–10 seconds,” set the **hard** bounds to `3*fps` and
`10*fps`; the default preferred band alone does not enforce that instruction.
For a 60-second, 60-FPS film, `target_frames` is 3600. Omit shot-count fields unless
requested. Do not manufacture a long hold or duplicate shots to fill runtime.

**Approve all videos (N)** approves every drafted video listed in the window,
not just the visible page. The batch can include other projects already in the
review store. Use **Next video** to inspect it; the tooltip and explanatory text
state the scope. The list and exact revisions are captured at opening: new drafts
do not silently join, and a changed revision or save failure rejects the whole
approval. Existing approvals remain unchanged unless a new comment is submitted.
Export exceptions are never granted by this button.

A typed comment is temporary until **Submit** or **Approve all videos (N)**.
Submit saves the current video's comment and closes without approval. Approve
saves its comment plus batch approval and closes. Escape discards unsent input.
On a submitted comment, read it and revise the plan if needed; the agent presents
the revised contract through the same tool. Do not ask for another manual open.

The tool waits up to 50 seconds. After `timeout`, continue `production_status`
with `project_id`, `draft_hash`, `after_submission_sequence`, and
`wait_timeout_ms: 50000` from the returned cursor. Read-only status never opens
a window. A delayed submission is durable across AFK periods and reconnects.
Keep the turn active; the wait cannot wake an already-ended host agent turn.
Read the exact saved `locked_hash` before proceeding. Approval survives screen
closure and restart, so an unchanged approved plan needs no second approval.
A `visible` contract can be any member of the currently open batch; changing the
selected page is not dismissal. Fresh authority takes precedence over an older
closed/timeout response. Neither closure nor timeout grants approval.

## Physical input and revocation diagnostics

Mouse movement, clicks and scrolling do not themselves revoke the director
lease. Conflicting mouse operations can be suppressed during controlled work;
this is not a general free-camera inspection mode. A physical click on the
explicit Revoke director control or Emergency stop button still takes effect.

When keyboard takeover is enabled, non-exempt **key presses** revoke control,
including Escape or other menu keys outside review screens, not only WASD.
The controls key (default F10) and typing in Replay MCP control/review screens
are exempt. Emergency stop (default F12) always takes priority; configured
Minecraft bindings are authoritative. No automatic recovery after human takeover.

For a user-authorized debugging task, inspect the game's
`.replay-mcp/audit/audit.jsonl` `lease.revoked` entry. Its `details.trigger` can
contain `source`, `control`, `key_code`, `scan_code`, `modifiers`, and `screen`.
Sources distinguish `keyboard_press`, `control_screen`, and `bridge`; control
identifies the symbolic key or explicit button/RPC. Screen is a class name or
`gameplay`, not captured screen contents. Older logs may lack trigger details;
do not infer an exact key from `human_override` alone. Do not print connection
secrets or unrelated audit payloads. This debugging permission is not permission
to inspect unrelated user files during ordinary filming.

## Preset fields and profiles

`replay_camera_preset` takes `base_revision` from `replay_timeline_get`, optional
`project_id` plus persisted unique `shot_id`, and a `parameters` object. It
replaces the camera and replay-time tracks, not just one isolated keyframe.
Save work between shots. Use `replay_timeline_apply` for unusual/manual requests;
it remains supported and subject to the same final evidence checks.

Every preset requires `duration_us`, `replay_start_us`, and explicit
`replay_time_mode: freeze | advance_1x`. Times are integer microseconds aligned
to milliseconds. Basic duration is at most 60 seconds; editorial contract bounds
still apply. Optional `replay_end_us` equals start for freeze, or start+duration
for advance_1x. Do not silently retime a player's action.

| Preset | Inputs and behavior |
| --- | --- |
| `static` | `start`, `yaw`/`pitch` or `aim`; constant camera position |
| `slide` | `start`, `direction` with y=0 (default +X), signed `distance`; horizontal translation |
| `rise` | `start`, signed `distance`; negative falls |
| `push` | `start`, signed `distance`; direction defaults to yaw/pitch, negative pulls back |
| `pan` | `start`, starting yaw/pitch, `sweep_degrees`, `tilt_degrees`; fixed position, rotating view; fixed aim conflicts |
| `orbit` | `center`, optional radius/height/start_angle_degrees/sweep_degrees/aim; aims at center by default; yaw/direction conflict |
| `follow` | `player_uuid`, source start/duration, advance_1x, seed, horizontal distances, elevation and actual follow_fps |

`profile: exterior` is the default; `interior` shares the same generators with
conservative defaults. Slide/push distance is 8 exterior / 2 interior blocks;
rise is 5 / 1; pan sweep is 60 / 30 degrees; orbit radius is 12 / 3 blocks.
Interior support resolves at the starting XZ with start.y as search ceiling,
then sets camera height to ground support+1.6. It is not continuous floor or
stair following. Without an explicit yaw or aim, interior non-orbit shots use
an open-space orientation. `interior_height_mode: explicit` retains the supplied
height. Orbit uses its actual initial XZ for support and explicit `height` overrides
that default. Explicit creative inputs are not permission to silently move them.

Example parameters for a five-second frozen interior horizontal slide, using
newly scouted coordinates rather than copying this placeholder position:

```json
{"preset":"slide","profile":"interior","start":{"x":10,"y":70,"z":10},
 "direction":{"x":1,"y":0,"z":0},"distance":2,
 "duration_us":5000000,"replay_start_us":10000000,"replay_time_mode":"freeze"}
```

## Checked follow and source selection

Discover the actor with `game_query(kind: entities, type: minecraft:player)` and
retain the returned UUID. Runtime numeric IDs serve sampled subject validation;
they are not the follow UUID. Empty metadata `players` or `selfId:-1` does not
prove a local actor is absent. If needed, inspect the unfiltered bounded entity
snapshot: native coverage is currently 32 blocks around the camera even if a
larger query distance is requested. Updated clients emit canonical registry IDs.

Follow defaults: `follow_min_distance: 3`, `follow_max_distance: 5` horizontally;
`follow_elevation: 1.5` above the eye, not feet; `seed: 0`; `follow_fps: 60`.
The checked planner searches rear/side candidates and lowers elevation toward
zero when space requires. Heading follows travel and is retained at stops;
seeded variation is reproducible, not fresh arbitrary noise. Saved seed and
parameters are part of the regeneration checkpoint. Planner speed defaults are
12 blocks/s and 90 degrees/s; explicit `max_speed`/`max_angular_speed` can change
these limits. They do not replace contract/geometry checks or imply a global
camera-speed policy from another branch.

Native trace requires source start >1 second, duration a multiple of 50 ms and
at most 50 seconds, and a 50 ms source tail. FPS is 20/40/60/80/100/120. Scratch
native rendering can take up to 150 seconds; scratch output is trajectory
proof, not a reviewed preview. Complete packet history includes preroll/tail.
A clean output interval can still fail because its preroll contains a pose,
metadata, teleport, chunk/block or unsupported packet. The response's reason
and interval are authoritative; no broad relaxation of the packet allowlist.

Select another valid interval **inside the accepted take**, use appropriate
fixed coverage, or capture another authorized performance when needed. Do not
substitute an idle interval outside accepted action bounds merely because it
passes. Fixed geometry proof and visible stationary actors do not prove requested
movement. New agent performance still requires rehearsal and one uninterrupted
ordered action batch; player-led capture remains lease-free.

## Clearance, previews and durable timing

Checked generation verifies the final native path and rolls back a blocked or
unverified edit when its lease remains valid. After takeover, inspect state and
stop rather than reacquiring to roll back. `skip_collision_check: true` records
skipped evidence; it never means safe, never satisfies `require_collision`, and
cannot bypass missing follow trajectory/timing evidence. Do not set it just to
hide a failure. Player-requested geometry passage must also be represented in
the contract; a call parameter cannot grant a physical final-export exception.

Frozen linear clearance uses `native-linear-frozen-sweep/3`; advancing linear 1x
static geometry uses `native-linear-packet-static-sweep/1`; checked native follow
uses `native-follow-tick-envelope/1`. A half-block clearance box is swept along
segments, including intermediate obstacles. Unloaded coverage, changing/moving
or unsupported shapes, nonlinear paths and exhausted budgets fail closed.
Standard near-plane coverage is bounded by FOV<=110, aspect<=4 and no stabilization.
Entities and visible noncollision geometry are outside this block-shape guarantee.
Conservative eye-ray visibility is not proof of full-body artistic composition.

Follow FPS/full-output-range requirements persist separately from clearance,
keyed by immutable source and native path hashes. Generic or skipped collision
rechecks and replay reopening cannot erase them. Render the complete checked
plate at the bound FPS; native video preview defaults to 30 FPS, so explicitly
set `fps` to `follow_fps` (often 60). To change FPS or native range, regenerate.
Trim completed plate frames later in controlled assembly. A sampled geometry
analysis may say valid at a different FPS without authorizing a native video.

Frame/contact-sheet previews use `replaymod-native-still/1`, with width/height
(default 640x360) and authored endpoint samples. Inspect their actual pixels or a
completed draft video. `replay_validate_range` is geometry-only; its valid flag
is neither image acceptance nor whole-path clearance. Final `render_start`
requires a matching successful validation job, unchanged timeline and range.
The interpolation-order fix refreshes native paths before matching trace source
time; on a current client, recurring trace mismatch deserves diagnosis, not bypass.

## Controlled edit and final check

Persist unique shot IDs before generation. Pass `project_id`/`shot_id` on presets,
previews, range validation and renders. After inspecting completed media, call
`production_visual_review` with those IDs, its `job_id`, `outcome: accepted | revise`,
and concrete `findings`. Unbound historical preview jobs cannot be retroactively
certified by attaching notes. These are agent observations, not player authority.

1. Collect completed **render** job IDs for final plates, with current checksums,
   native lineage, clearance receipts, correct dimensions/FPS and frame counts.
2. Call `production_edit` with `project_id`, current **production edit_revision**
   as `base_revision` (not project/timeline revision), and
   `edit: {clips: [{render_job_id, in_frame, out_frame}, ...]}`. Frame trims are
   zero-based and end-exclusive. Full three-second 60-FPS plate: 0..180.
3. Call `production_assemble`, follow its export job to completion, and inspect
   actual master frames, especially cuts, first/last frames and motion beats.
   Assembly reads current locked authority; it does not need a director lease.
4. Call `production_check` on the actual master. Report publication needs control;
   keep or acquire it for that scoped step, then release after bridge jobs finish.
   Assembly completion or structural `project_validate` is not final certification.

Version 1 certifies straight cuts and integer-frame trims. No padding, transitions,
audio, overlays or retiming are supported; external exports have no trusted
assembly receipt. Do not label unsupported requirements satisfied or discard them.
Final checks verify actual file metadata/hashes, lineage, runtime, editorial spans,
reuse and required collision evidence. Adjacent contiguous fragments of the same
lineage merge into an editorial shot; splitting one long hold into edit entries
cannot evade bounds. Reuse detects overlapping lineage and exact decoded frame
identities across editorial spans, including renamed/rerendered copies. It is not
a general semantic-similarity detector or independent style judge.

PASS means supported mechanical checks passed. FAIL means known violations;
INCOMPLETE means missing/unsupported evidence. Preserve every finding. Repair
ordinary failures; propose a physical exception only for a genuine player-requested
feature. The tool itself auto-presents unresolved assembled-export findings, so
current UI may open even for a repairable failure: do not claim agent classification
suppresses that automatic popup. No mandatory human visual-review gate exists.

The separate export screen offers **Accept exceptions**, **Reject and revise**,
and **Later**. Exceptions bind to the exact export/report and produce
PLAYER_OVERRIDDEN, never PASS. Later/Escape/timeouts defer without decision.
Continue AFK waits through `production_status` using `export_binding` and
`after_decision_sequence`; no simulated input or override parameter exists.
Eight assemblies / three identical consecutive failures exhaust the repair budget;
exhaustion does not waive rules or justify resetting state to continue blindly.

## Recovery, upgrades and supported scope

After compaction/restart read the request record, `project_get`, offline-capable
`production_progress`, fresh `production_status`, and job state before mutations.
Progress reports planned/rendered/blocked shots, usable/remaining frames, current
path/geometry/visual evidence, historical completion and repair budget. Shot
binding version2 includes enclosing scene/take production context and project
FPS/resolution. Changed source/range/settings makes affected evidence stale;
independent sibling edits and editorial notes need not invalidate other shots.
Do not rewrite old binding records to make stale media look current.

Completed verified plates are reusable for recovery of the same production.
This is not permission to reuse earlier authored paths for a new standalone
request. Revalidate historical completion before delivery. A fresh observer must
not mark another live owner's work interrupted. Proven-dead job owners are
failed/interrupted, never converted into completed media. A file left by a dead
renderer is not a certified receipt. Startup-rejected native preview jobs now
become failed with their original error; do not wait forever for an orphan queued
job or manufacture its completion. There is no in-place native render resume.

Finalized replay metadata/import works while its working copy is open; do not
close it merely to read immutable source metadata. Never read an active unfinished
recording as finalized input. Preserve source and working-copy identities.

Native mod changes require Minecraft restart; template/sidecar changes require
packaging and a fresh Codex/plugin session. Reinstalling does not hot-reload an
existing MCP process. With explicit debugging authorization, target only the
confirmed dev-client PID for shutdown/relaunch via `./gradlew runClient`, after
protecting recording/render writes; this is not standing filming permission to
kill clients. After any restart, rediscover the instance. `game_perform` requires
a live player and cannot open a world from the title screen. Never retry through
human_override without explicit recovery authority.

Scoped live acceptance established moving/turning and stationary follow plates,
actual native output, deterministic repeats, timing guards, open-replay import,
preview notes, interrupted native-owner recovery, and a two-shot six-second
controlled export with no reuse. It does not establish universal passage through
terrain, doorways, narrow corridors, low ceilings or impossible offsets. Use fresh
geometry and actual frames for each production, not this historical success.
