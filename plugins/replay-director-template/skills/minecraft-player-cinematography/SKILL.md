---
name: minecraft-player-cinematography
description: Plan and author third-person Replay Mod coverage of a Minecraft player performing actions or traveling. Use for player-subject framing, fixed-vantage coverage, route coverage, or an explicitly desired follow shot; do not infer first-person.
---

# Minecraft Player Cinematography

The player is the subject, not the camera. “Capture the player doing X” means
third-person/free-camera footage unless the user explicitly says first-person.

For the mandatory production-memory and information boundaries plus
tool/control contracts, read
[Replay MCP operations](../replay-director-workflow/references/replay-mcp-operations.md).

## Default to fixed observation

For player action or movement, first choose a fixed camera position with a
clear view of the route or action area. Keep the player roughly within 15
blocks when practical. Hold the camera there and let the player cross the
frame; do not convert “going from X to Y” into a follow shot.

Use this coverage decision:

| Movement | Default coverage |
| --- | --- |
| Local action or short movement visible within about 15 blocks | One fixed camera |
| Travel that leaves one useful view | Several fixed cameras, rendered as separate shots and cut together |
| Purposeful medium-distance travel, roughly 20–80 blocks | Fixed coverage remains safe; a follow shot is also suitable when it improves the requested style |
| Long travel, complex terrain, or building interiors | Several fixed exterior/interior viewpoints; avoid one continuous follow |

The user may override these choices. The checked follow preset defaults to a
horizontal distance of 3–5 blocks and elevation 1.5 blocks above the player's
eye, with seeded rear/side variation and lower elevation where ceilings require
it. This elevation is eye-relative, not measured from the feet. Manual offsets
remain available for unique requests; preserve visibility and avoid jitter.

## Build the shot

1. If spatial maps are available, use a coarse surface map and bounded size-8
   or size-4 refinement to identify clear fixed-camera regions along the action
   area or route. Use volume only where interiors, overhangs, ceilings, or
   stacked geometry invalidate the surface view. Then inspect the useful
   candidates visually rather than collecting screenshots from every position.
2. Choose positions with foreground/background separation, an unobstructed
   subject, and enough lead room in the direction of travel.
3. For long routes, place fixed views at meaningful beats such as departure,
   a landmark, a doorway, an interior action, and arrival. Each view should
   show the player at a reasonable distance rather than trying to see the
   entire journey at once.
4. Let a fixed shot breathe briefly before entry and after exit so the editor
   has handles. Preserve normal 1:1 speed unless the user requests otherwise.
5. Review with low-quality previews and reject shots where the player is
   hidden, too distant, clipped, or visually confused with the background.

## Camera safety and transitions

Keep ordinary camera paths outside walls and terrain. Switch fixed positions by
rendering separate clips and combining them in post-production. Only when the
user asks for a **direct smooth transition** may the camera traverse geometry
between old and new positions; make that transition about 0.5–1 second. The
user may explicitly grant broader permission to pass through geometry.

Player footage may be intercut with a continuous building/scene move. End one
coverage mode cleanly before starting the other so a fixed player camera is not
accidentally turned into an orbit or follow path.

## Checked follow and recovery

Read [detailed follow and recovery procedures](../replay-director-workflow/references/production-camera-recovery.md)
for exact parameters, timing guards, unsupported source intervals and evidence
handoffs before using the checked follow workflow.

Use `replay_camera_preset` with `preset: follow`, player UUID, saved seed,
`advance_1x`, source `replay_start_us` and authoritative `duration_us`; optional
`replay_end_us` must equal their sum. The candidate planner preserves the requested
horizontal range (default 3–5 blocks), searches eye-relative elevation down from
1.5 toward zero for ceilings, keeps stopped heading, and validates connecting
segments, conservative eye visibility and the smoothed final native path.

The native renderer traces joined 50 ms eye interpolation envelopes. Set
`follow_fps` to the actual final FPS: supported values are 20, 40, 60 (default),
80, 100 and 120. Source start must exceed one second, duration must be divisible
by 50 ms, and the replay needs 50 ms after the requested end. Generation has a
bounded trace time; expect native scratch rendering before path application.
This scratch render is trajectory evidence, not an accepted visual preview.

The supported advancing-world policy requires a complete packet interval proving
unchanged geometry, including trace preroll/tail. Block/chunk changes, moving
pistons, modded shapes, unknown/custom packets, pose changes, teleports, missing
players, uncovered chunks, tick discontinuities and exhausted budgets fail closed.
Do not freeze a requested follow or shrink the horizontal range to get a pass.
Blocked intervals may need separate fixed coverage or an explicitly changed brief.
Explicit collision skipping is recorded as skipped and cannot satisfy required
safety. It does not bypass missing native trajectory evidence.

Supply `project_id` and a persisted `shot_id` when generating and previewing or
rendering. Read `production_progress` after context loss/restart; it derives shot
counts, blocked intervals, independent evidence and unfinished jobs from disk.
Inspect actual preview frames, then retain observations with
`production_visual_review`; those notes never override mechanical failures.
Follow duration remains subject to the production contract's editorial shot bounds.
Render the complete checked plate at the bound FPS, then trim in controlled assembly;
a native range trim needs regenerated subject timing proof.
Final visual/native acceptance must be established on the loaded client version;
source tests and a generated path alone do not establish attractive coverage.

Discover the actual actor with `game_query(kind:"entities", type:"minecraft:player")`
and use its returned UUID. Empty ReplayMod metadata `players` or `selfId:-1`
does not establish a missing local actor: its injected spawn can exist without
that metadata. If a typed query is unexpectedly empty, inspect an unfiltered
entity query and its bounded coverage before declaring the recording unusable.
Native observations cover at most 32 blocks around the camera; requesting a larger
distance currently filters that same bounded snapshot.
