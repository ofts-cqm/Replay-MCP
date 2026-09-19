---
name: replay-director-workflow
description: Direct a Minecraft scene, record logical Replay Mod takes, edit and visually verify replay shots, render outputs, and export an editor handoff through Replay MCP. Use for Minecraft filmmaking work; do not use for ordinary gameplay or unrelated video editing.
---

# Replay Director Workflow

Use Replay MCP as an evidence-driven filmmaking loop. The Minecraft client and
Replay Mod remain authoritative; never describe a queued operation as complete
until the corresponding result or job says it completed.

## Start safely

1. Call `system_status`. If offline, report the searched game directories and
   ask the user to start a prepared client with the Replay MCP Fabric mod.
2. Check runtime mode, command policy, flight policy, Replay Mod capability,
   and artifact roots before relying on them. Commands cannot be enabled by a
   tool. Flight is available only when Minecraft already grants it.
3. Create or open the production project before recording if the user wants a
   reusable handoff. Keep intent, shots, excluded ranges, FOV/look-at metadata,
   captions, and music notes in the project; do not call those native Replay
   Mod tracks when capability data says otherwise.
4. Acquire `control_acquire` immediately before the first mutation. A busy
   lease is a stop condition, not permission to steal control.

## Direct the live performance

- Use `game_observe` before acting. Prefer `annotated` when selecting entities,
  blocks, or widgets by stable observation ID.
- Work in bounded observe-act-verify loops. Send small ordered
  `game_perform` batches with explicit postconditions and useful checkpoints;
  observe again after movement, commands, interaction, or a scene transition.
- When `capabilities.navigation.ground` is available, prefer bounded
  `navigate_to` actions for fixed-coordinate ground travel. Keep targets inside
  loaded terrain, observe after arrival, and fall back to short manual movement
  batches when the planner reports unsupported terrain or no path.
- Treat physical-input override, lease loss, disconnect, timeout, and stale
  fencing as terminal for the current control session. Do not automatically
  reacquire or replay a mutation.
- Use arbitrary server commands only when the local command policy enables
  them and the requested scene needs them. Record transition-out/in markers
  around teleport or world-change footage. Existing server permissions remain
  authoritative.

## Record logical takes

Replay Mod recording is connection-scoped. `recording_start` and
`recording_stop` mark logical takes; they do not create independent finalized
files. Add meaningful action, cut, transition, mistake, and note markers.
After stopping, preserve the returned take ID and pending-finalization state.
The replay normally finalizes only after the Minecraft connection closes.

When the take is accepted and disconnecting the current world is intended, use
the finalization job returned by `recording_stop` with
`recording_finalize_and_open`. Follow that job through `disconnecting`,
`finalizing`, and `opening`; do not treat the replay as available until the job
completes. If automated finalization is unavailable or fails recoverably, keep
the job/take link and fall back to the explicit user-controlled disconnect.

The logical start/end markers use the same versioned clip grammar as
player-led capture. After finalization, call `project_import_replay` with the
current project revision, scene, finalized source path, take ID, and
`provenance: agent`. Use its returned `CapturedTake` as the source-range handoff
so agent and player performances enter replay editing through one contract.

## Edit, preview, and render

1. Open the replay. Source recordings are immutable; edits use the working
   copy and revision-checked timeline/project operations.
2. Inspect `replay_timeline_get`, then apply small coherent batches with the
   exact base revision. On a revision conflict, re-read and reconcile rather
   than overwriting.
3. Treat preview ranges as authored output time, not raw replay time. Use
   `replay_preview` contact sheets or `draft_360p` frame/video ranges to revise
   an edit without a full-quality render.
4. Run `replay_validate_range` for each final shot plate. Resolve unloaded
   chunks and camera-collision errors; review proximity and occlusion warnings.
   Pass its completed job ID to the final-quality `render_start` call.
5. Follow render progress with `job_get`. A returned job ID means started, not
   rendered. Long bridge jobs keep the lease active, but human override or an
   actual lease loss is still terminal for further mutations.
6. Read completed artifacts through their `replay-mcp://artifact/...`
   resources and verify that expected duration/framing/output details match
   the project intent.

## Handoff and recovery

- Run `project_validate` before `project_export_handoff`. Export only after
  references and ranges are valid; report warnings such as unrendered shots.
- On cancellation or render failure, keep incomplete-output metadata but do
  not register or present it as a valid finished artifact.
- On recording finalization delay, preserve the take/project link and resume
  from `recording_status`/`replay_list`; do not fabricate a replay ID.
- On capability or policy denial, offer a workflow that stays within reported
  capabilities. Never claim unsupported editorial tracks are native.
- For the optimized editor workflow, retain the editable `.mcpr`, render only
  validated story-beat shot plates, and use SynthCut as the sole final timeline
  for transitions, titles, grading, audio, and master export. Do not build a
  second polished native master unless the user explicitly requests one.

Always call `control_release` in the final cleanup path, including after an
error. If release cannot be confirmed because the bridge disconnected, state
that the mod's disconnect/TTL cleanup is authoritative.
