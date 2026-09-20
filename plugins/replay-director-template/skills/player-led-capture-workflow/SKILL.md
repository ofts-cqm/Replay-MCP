---
name: player-led-capture-workflow
description: Turn a player-controlled Minecraft performance marked with Replay MCP keybinds into one multi-scene video with authored replay cameras and verified renders. Use when the human player performs live; use replay-director-workflow when Codex controls the player.
---

# Player-Led Capture Workflow

Treat multiple requested actions or locations as scenes in one final video
unless the user explicitly requests separate videos.

Read [Replay MCP operations](../replay-director-workflow/references/replay-mcp-operations.md)
for the mandatory production-memory and information boundaries and the deployed
tool, lease, recording, and job contracts. Read the applicable
[player](../minecraft-player-cinematography/SKILL.md) and
[building/scene](../minecraft-building-cinematography/SKILL.md) camera skills
before authoring shots.

## Keep live performance lease-free

During player performance, do not call `control_acquire`, `game_perform`,
`recording_start`, or `recording_stop`. Replay Mod owns the connection-scoped
recording; local keybinds annotate source ranges without transferring player
control to the agent.

1. Call `system_status` and `recording_status`. Confirm recording is armed and
   `capabilities.player_clip_capture` is available.
2. Create or read one production project with ordered scenes. Preserve its
   exact revision for import. Create the temporary request record, preserving
   the initial request verbatim and its absolute path for every later handoff.
3. Explain the configured keybinds. Defaults are F6 start, F7 accept/end, and
   F8 revoke; Minecraft Controls is authoritative if remapped.
4. Let the player perform normally. One active clip must end or be revoked
   before another starts. An unmatched start is incomplete, never accepted.
5. Ask the player to leave the world only when the capture session is complete,
   so Replay Mod can finalize the immutable connection-scoped `.mcpr`.

The agent must not modify or interact with the world during this phase unless
the user separately and explicitly grants that authority. A filming request is
not scene-setup permission.

## Import accepted ranges

Use `replay_list` and `replay_get` to identify the finalized source, then call
`project_import_replay` with the project ID, current base revision, scene ID,
source replay, and `provenance: player`. This import is lease-free.

Use the returned `CapturedTake` exactly:

- accepted clips provide authoritative `replay_in_us` and `replay_out_us`;
- revoked, incomplete, duplicate, malformed, and out-of-range attempts remain
  diagnostics and never become shots; and
- repeated import at the current revision must not duplicate takes or shots.

On revision conflict, re-read and reconcile. Never edit the source `.mcpr` or
open a still-recording replay.

## Author replay shots

Acquire control once immediately before `replay_open`, and retain it through
the contiguous playback, camera editing, preview, validation, and render phase.
Do not reacquire before each call; the sidecar owns renewal.

Inspect the existing timeline before authoring. If camera or time edits predate
the current request, preserve them and start with a fresh working copy or empty
timeline. Do not reuse any previous camera position, time position, speed change,
or edited range without the user's explicit approval.

Clip timestamps are discovery bounds, not finished shots. Default to
third-person/free-camera coverage, never first-person unless the user asks.
For internal review use contact sheets, frames, or `draft_360p` video. Validate
accepted final ranges, render shot plates, and inspect actual rendered frames.

Use phase-specific subagents by default when supported. Release the
capture/import subagent after it hands off the request-record path, project/take
IDs, accepted ranges, and diagnostics. Use a separate replay-camera context,
then append the Replay-edit request check and release it before post-production.
Only one agent may control the Minecraft instance. If a governing restriction
prevents required release, ask the user before continuing.

Finish with [Replay post-production](../replay-post-production/SKILL.md),
project validation, handoff export, and one `control_release` in final cleanup.
On human override or lease loss, stop instead of repeatedly reacquiring.
