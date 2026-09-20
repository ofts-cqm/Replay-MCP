---
name: replay-director-workflow
description: Direct an agent-performed Minecraft production from scouting through Replay Mod capture, replay camera work, verified shot plates, and editor handoff. Use when Codex controls the live player; do not use for ordinary gameplay or a player-controlled performance.
---

# Replay Director Workflow

Treat the request as one production unless the user explicitly asks for separate
videos. “Record this, then record that” normally means multiple scenes in one
final video, not one output per phrase.

Read [Replay MCP operations](references/replay-mcp-operations.md) before using
the tools. It is the deployed-agent reference for control, runtime contexts,
recording finalization, jobs, and the public tool groups.

## Establish the production boundary

1. Call `system_status`; select the instance explicitly when more than one is
   live. Check the runtime mode, policies, capabilities, and artifact roots.
2. Create or open one project and translate the request into ordered scenes,
   shots, and a single intended deliverable.
3. Select the applicable shot pattern:
   - read [Minecraft player cinematography](../minecraft-player-cinematography/SKILL.md)
     for any player action or travel;
   - read [Minecraft building cinematography](../minecraft-building-cinematography/SKILL.md)
     for structures, interiors, landscapes, or environmental coverage; and
   - use both for a production that switches between subject and scene coverage.
4. Default to third-person/free-camera coverage. Never infer first-person from
   “capture the player doing …”. Use first-person only when the user explicitly
   requests it.
5. Default to normal 1:1 world time. A building or environment shot may freeze
   replay time because elapsed action is immaterial. Apply fast-forward,
   slow-motion, or another speed only when requested or clearly approved.

## Preserve the world

Observation, camera placement, and requested player locomotion are allowed.
Do not attack, use, open, break, place, pick up, drop, rearrange inventory,
execute scene-changing commands, teleport, alter time/weather, or otherwise
interact with or modify the world without explicit user permission. If the
requested result appears to require any such operation, ask before doing it.
Permission for filming is not permission for scene dressing.

## Scout and rehearse before recording

- Inspect every road, doorway, interior, endpoint, and planned camera area with
  `game_observe`, `game_query`, and additional views. Do not invent geometry
  from a map or a single frame.
- Acquire control once, immediately before the first lease-required rehearsal
  or mutation. Keep that lease through the contiguous capture phase; do not
  call `control_acquire` before each action or edit, and do not send manual
  heartbeat calls.
- Rehearse routes outside the accepted recording. Try bounded paths, verify
  arrival and visibility, and revise any route that needs unapproved world
  interaction.
- Prepare the whole performance as one ordered `game_perform` action batch.
  The accepted recording must contain exactly one performance batch with no
  MCP pause between movement segments. Consecutive movement actions belong in
  that batch.

## Capture a clean take

1. Confirm Replay Mod is armed with `recording_status`.
2. Start one logical take, add useful action/transition/mistake markers, and
   execute the single prepared performance batch.
3. Verify the returned trace and final state. If the performance fails, stop
   and reject that attempt, diagnose and rehearse, then record a fresh take.
   Do not hide a long inter-call pause by stitching multiple action batches into
   the same accepted performance.
4. Stop the logical take. A stop marker does not finalize an independent file;
   the `.mcpr` remains connection-scoped.
5. When leaving the world is intended, run `recording_finalize_and_open` and
   follow its job through completion. Otherwise preserve its pending state for
   a later explicit disconnect.
6. Import the finalized immutable source with `project_import_replay` and
   `provenance: agent`. Edit only the working copy.

## Change contexts deliberately

End live-game reasoning before authoring the replay camera. Re-read the project,
accepted source ranges, and `replay_timeline_get`; do not send `game_perform`
while reasoning about `replay_playback` or camera keyframes.

If subagents are available and their use is permitted, use at most one
controller for a Minecraft instance. A capture subagent should hand off the
project/take IDs, accepted ranges, observations, and unresolved warnings, then
be released before a replay-camera subagent starts. Release the camera subagent
after shot plates and validation evidence are handed to post-production. This
phase boundary keeps live controls, replay controls, and editor controls out of
the same working context.

## Edit and review efficiently

1. Open the replay working copy and make small revision-checked timeline
   changes. On conflict, re-read rather than overwrite.
2. Keep the camera out of walls and terrain during ordinary shots. Default
   scene changes to separate clips for the post-production editor.
3. For every internal review, use `replay_preview` with contact sheets, frames,
   or a `draft_360p` video. Do not spend a final-quality render on an edit that
   has not passed low-quality review.
4. If the user requests a **direct smooth transition**, a 0.5–1 second camera
   move may connect the old and new scene directly and may pass through walls
   or terrain. Outside that transition, geometry passage requires the user’s
   explicit permission.
5. Run `replay_validate_range` on each accepted final shot plate. Resolve
   unloaded chunks and camera-in-solid errors and review subject-distance and
   occlusion warnings.
6. Start final-quality renders only with the matching completed validation job,
   then follow each job to a terminal result and inspect actual rendered frames.

## Finish in post-production

Read [Replay post-production](../replay-post-production/SKILL.md). When a
capable editor is available, use Replay for camera/timing work and validated
shot plates; do most trimming, scene ordering, combining, and transitions in
the editor. Maintain one final timeline and one master unless the user
explicitly asks for multiple outputs.

Validate the project and export the handoff. Release control exactly once in a
final cleanup path after all Replay MCP mutations and bridge-backed jobs are
finished. If human override, disconnect, stale fencing, or genuine lease loss
occurs, stop mutations and report it; do not loop on reacquisition.
