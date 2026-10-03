---
name: minecraft-building-cinematography
description: Plan and author smooth Replay Mod camera coverage of Minecraft buildings, interiors, landscapes, and static scenes. Use for establishing views, architectural reveals, flyarounds, and continuous environmental camera paths rather than player-subject coverage.
---

# Minecraft Building Cinematography

For the mandatory production-memory and information boundaries plus
tool/control contracts, read
[Replay MCP operations](../replay-director-workflow/references/replay-mcp-operations.md).

Building and scene coverage is camera-led. Unlike player coverage, the camera
should normally move in one deliberate, continuous trace throughout each shot.

## Shot length, motion, and coverage

1. Recommended shot lengths are **3–10 seconds**, with absolute bounds of
   **1–15 seconds**, unless the user explicitly sets different bounds. The broad
   window encourages variation: for example, a few 3–4-second shots followed by
   a few 8–10-second shots. Choose lengths for the visual beat and vary short and
   long shots across the sequence; there is no universally preferred duration
   such as seven seconds. The example is not a required repeating pattern.
2. Do not use the `static` camera preset or an equivalent motionless manual path
   for a plain static or paused scene/building. Use a deliberate moving camera.
   Static camera presets are for dynamic subjects such as player activities,
   entity activities, or operating redstone machines; these are examples, not
   an exhaustive list. Freezing replay time does not justify freezing the camera.
3. A long-distance overview must show the target clearly **at the center of the
   capture**, with enough edge clearance to read its full form. Visibility alone
   is insufficient. Aim at the visual center of the whole building/scene and
   verify the actual rendered composition; a coordinate or preset aim alone is
   not proof of centered framing. Check the beginning, middle, and end, plus any
   turning point where the target could drift away from the center.
4. For scene and building-exterior coverage, vary between long-distance
   overviews and close detailed views. Take inspiration from aerial photography:
   show the whole target from high altitude, then switch to close, low-altitude
   details. Use `orbit` arcs to reveal its magnitude and form, and `rise` moves
   to reveal its height and vertical scale. Choose safe distances, altitudes,
   and movement speeds from the actual target; do not impose one fixed cadence
   or assign every overview/detail shot the same length.

## Preset and clearance support

Use `replay_camera_preset` for parameterized slide, rise, push, pan, or orbit
shots when available. Reserve `static` for dynamic-subject coverage as described
above. Duration and replay-time mode are explicit. The shared
interior profile defaults to support +1.6 blocks and open-space orientation when
no aim is requested; explicit height is available. Current support resolves the
starting surface, not a smoothed stair-following floor trajectory.

`replay_path_clearance` checks native linear paths in frozen geometry with swept
clearance, including intermediate walls. Advancing linear 1x paths are supported
only when a complete packet scan proves static geometry; changing worlds,
unsupported packets, curves and unknown chunks remain unverified. Explicit skips remain skipped and cannot meet a contract
requiring verified collision safety. Rendered previews still establish composition. Push/pull defaults to the supplied
camera yaw/pitch; negative distance pulls back. Pan/tilt takes starting yaw/pitch;
combining it with a fixed aim is a conflict. Orbit uses its center/aim and resolves
interior support at the actual orbit start. Explicit orbit height overrides that
default. A rejected preset restores the prior timeline when the lease remains
valid; after takeover, stop and inspect state without reacquiring automatically.

See [preset fields and examples](../replay-director-workflow/references/production-camera-recovery.md#preset-fields-and-profiles)
for each movement, interior defaults and manual-path fallback.

## Survey before keyframing

1. When spatial maps are available, begin with a size-16 or size-32 surface map
   to locate the structure and broad camera clearance, then refine promising
   regions at size 8 or 4. Use volume only for interiors, ceilings, floating or
   hollow structures, overhangs, or unresolved vertical geometry. Stop once a
   plausible camera region is known.
2. Use a small set of visual observations from candidate positions and
   directions to judge appearance and composition. Inspect the front, sides,
   rear, height, surroundings, entrances, and any requested interior as the
   shot requires; one frame is not enough to infer the structure.
3. Choose a readable sequence of architectural beats: establishing context,
   approach or reveal, primary facade/form, significant detail or interior,
   and an exit or hero view. Vary short/long shot lengths and high overview/low
   detail coverage using the guidance above.
4. Keep the building framed with clearance at the edges and centered in
   long-distance overviews. Use foreground and parallax deliberately; avoid
   aimless full orbits when a shorter arc reveals the form better.
5. Connect camera keyframes into a smooth trace with controlled speed and
   easing. Avoid unintended stops, direction reversals, and discontinuities.

The replay world may be paused for a building/environment shot because event
time is usually irrelevant. Normal 1:1 time remains the default when moving
entities, weather, mechanisms, or another temporal detail matters. Honor any
explicit slow-motion or fast-forward request.

## Protect the scene

Do not open, break, place, move, activate, or otherwise modify the building or
world without explicit user permission. If an interior cannot be filmed
without opening or changing something, ask first.

Keep ordinary camera motion outside walls and terrain. Prefer multiple clean
shot plates and editor cuts when distant buildings or separate interiors cannot
be connected safely. If the user explicitly requests a **direct smooth
transition**, the camera may move directly through walls or terrain for about
0.5–1 second between scene positions. Broader geometry passage also requires
explicit permission.

Review the continuous motion with a low-quality preview before final rendering.
Check framing at the beginning, end, closest approach, turns, doorways, and any
point where collision or unloaded chunks are likely.
Before accepting the sequence, inspect actual preview/export frames for centered
overviews, overview/detail variety, short/long pacing, and deliberate camera
motion on static subjects. Correct material misses; mechanical validation alone
does not establish these composition and style goals.
