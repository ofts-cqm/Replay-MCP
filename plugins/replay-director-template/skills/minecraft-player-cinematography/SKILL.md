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

The user may override these choices. If using a follow shot, offset the camera
1–3 blocks laterally, vertically, or longitudinally rather than locking it
directly behind the player. Vary the offset gently and preserve visibility;
do not create constant random motion.

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
