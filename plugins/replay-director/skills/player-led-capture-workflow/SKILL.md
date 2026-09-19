---
name: player-led-capture-workflow
description: Turn player-performed Minecraft clips marked with Replay MCP keybinds into normalized replay takes, then edit, visually verify, render, and hand them off. Use when the player controls live gameplay; use replay-director-workflow when Codex should direct the live performance.
---

# Player-Led Capture Workflow

Keep human performance lease-free. During live capture, do not call
`control_acquire`, `game_perform`, `recording_start`, or `recording_stop`.
Replay Mod owns the connection-scoped recording; the player's local keybinds
only annotate accepted and rejected source ranges.

## Prepare capture

1. Read `system_status` and `recording_status`. Confirm Replay Mod recording is
   armed and `capabilities.player_clip_capture` is available.
2. Create or read the production project and ensure the intended scene exists.
   Preserve its exact revision for import.
3. Tell the player the configured clip keybinds. Defaults are F6 to start, F7
   to accept/end, and F8 to revoke; Minecraft Controls remains authoritative
   if they were remapped.
4. Let the player perform normally. An active clip must be ended or revoked
   before another starts. Do not synthesize inputs or acquire a lease.
5. Ask the player to leave the world when the capture session is complete so
   Replay Mod finalizes the immutable `.mcpr`. An unmatched start is an
   incomplete attempt, not an accepted clip.

## Import the finalized source

Use `replay_list` and `replay_get` to identify the finalized recording. Call
`project_import_replay` with the project ID, exact base revision, scene ID,
replay path or ID, and `provenance: player`. Import is lease-free.

Review the returned `CapturedTake` rather than inferring ranges yourself:

- accepted clips have explicit `replay_in_us` and `replay_out_us`;
- revoked, incomplete, duplicate, malformed, and out-of-range attempts remain
  diagnostics and never become shots; and
- repeating an import at the current revision must not duplicate takes or
  shots.

On a revision conflict, re-read the project and reconcile before retrying. Do
not open a still-recording replay or modify the source `.mcpr`.

## Edit after capture

Acquire `control_acquire` only when replay opening, playback, timeline editing,
preview, or rendering begins. From this point, follow the replay editing,
settled-range validation, rendering, visual inspection, handoff, recovery, and
guaranteed release guidance in `replay-director-workflow`.

Treat imported clip timestamps as discovery bounds, not proof of a finished
shot. Visually review accepted ranges, author the camera and output-time map,
run `replay_validate_range`, inspect actual rendered frames, and release the
lease in the final cleanup path.
