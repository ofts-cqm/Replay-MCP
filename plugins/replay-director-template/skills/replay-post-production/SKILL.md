---
name: replay-post-production
description: Assemble verified Minecraft Replay Mod shot plates into the final video with an available post-production editor. Use for trimming, ordering, combining scenes, transitions, titles, audio, grading, preview, and master export after Replay camera work.
---

# Replay Post-Production

Unless the user explicitly asks for separate deliverables, assemble all named
actions, locations, and capture beats into one timeline and one final video.
Do not turn “record A, then B” into two masters.

Read [Replay MCP operations](../replay-director-workflow/references/replay-mcp-operations.md)
for its mandatory production-memory and information boundaries. Also apply its
project, job, and artifact contracts when the handoff still involves Replay MCP.

## Divide work at the right boundary

Use Replay Mod for replay-time mapping, camera paths, clean shot plates, and
camera/chunk validation. When a capable post-production editor is available,
do most source trimming, scene ordering, combining, ordinary cuts, transitions,
titles, captions, audio, grading, and master export there. Do not build a
second polished master inside Replay Mod unless the user asks for it.

Before importing, require:

- the temporary request record and its absolute path; if absent or unreadable,
  ask the user to restate or confirm the request before editing;
- completed, checksum-verified shot artifacts;
- project/scene/shot IDs and intended order;
- frame rate, aspect ratio, resolution, and audio intent;
- visual-review findings and known warnings; and
- transition notes, especially any user-requested direct smooth transition.

## Edit efficiently

1. Create or inspect one editor project and one primary sequence.
2. Import each media artifact once and preserve stable IDs and source paths.
3. Trim and order scenes to the creative brief. Use ordinary cuts or editor
   transitions between separately rendered player/building shots by default.
4. Keep direct in-Replay camera transitions only when they were intentionally
   authored as 0.5–1 second moves; do not add a duplicate editor transition on
   top without a creative reason.
5. Generate the editor’s cheapest useful preview for internal review. If a
   Replay-side review is needed, use `replay_preview` or `draft_360p`, never a
   final-quality render merely to inspect an edit.
6. Check every scene boundary, requested action, audio cut, title, and final
   duration. Revise the editor timeline rather than re-rendering Minecraft
   footage unless the problem is inside a shot plate.
7. Export the final master once the timeline is approved, follow the export job
   to completion, and inspect actual frames from the exported video.
8. Reopen the request record and compare the completed export against every
   applicable requirement. Append the post-production result and correct any
   material mismatch before declaring the video finished.

Use a post-production subagent by default when supported. The replay-camera
agent must first hand off the request-record path, verified shot plates, and
validation evidence, then be released. If a governing restriction prevents
required release, ask the user before continuing. Post-production must not hold
or reacquire Minecraft control unless it discovers a shot defect that genuinely
requires returning to Replay; in that case finish the editor checkpoint first
and start a new, single-controller Replay phase.

Report the master path, technical metadata, checksum when available, included
scene order, and any unresolved visual or editorial warning. A project file or
queued export is not a finished video.
