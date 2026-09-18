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

When the workflow reaches a user-controlled disconnect boundary, explain what
must happen and wait. After reconnection or finalization, use `replay_list` and
`replay_get` to identify the finalized replay before editing.

## Edit, preview, and render

1. Open the replay. Source recordings are immutable; edits use the working
   copy and revision-checked timeline/project operations.
2. Inspect `replay_timeline_get`, then apply small coherent batches with the
   exact base revision. On a revision conflict, re-read and reconcile rather
   than overwriting.
3. Use `replay_observe` for interactive camera checks and `replay_preview` for
   sampled frames, a contact sheet, or a low-resolution video. Inspect image
   evidence and the completed preview job before approving a final render.
4. Run `render_validate`, resolve its errors, then start `render_start` or
   `render_still`. Follow progress with `job_get`. A returned job ID means
   started, not rendered.
5. Read completed artifacts through their `replay-mcp://artifact/...`
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

Always call `control_release` in the final cleanup path, including after an
error. If release cannot be confirmed because the bridge disconnected, state
that the mod's disconnect/TTL cleanup is authoritative.
