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

## Final completion evidence

For a mechanically certified straight-cut film, use `production_edit`,
`production_assemble`, and `production_check` as described in the operations
reference. `production_contract_draft` automatically presents contract review and
waits for physical submission; inspect the returned review state. Do not
send the player to F10 as the normal proposal flow. Only the player can lock or
override; neither a persisted draft nor a displayed window is approval. Reuse an
already locked matching contract rather than requesting approval again.
The v1 controlled assembler does not support audio, overlays, transitions or
retiming. External editors remain available for creative work, but their exports
are uncertified until supported provenance is available. Do not call a film complete
from runtime or a self-reported shot list alone. Repair failures automatically;
collect any necessary exception proposals for the player, preserving failed rules.
For a failed assembled export, `production_check` automatically opens the separate
export-results review. Verify `export_presentation` and the exact report binding;
do not ask the player to navigate F10. Opening review never grants an override.

## Divide work at the right boundary

Use Replay Mod for replay-time mapping, camera paths, clean shot plates, and
camera/chunk validation. For a certified straight-cut deliverable, use the built-in controlled assembler
for the actual final master. Use an external editor when the requested work needs
unsupported transitions, titles, audio, or grading; report its certification
limitation. Do not send a supported straight-cut film to an external editor and
then imply it has a controlled assembly receipt. Do not build a
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

1. Choose the controlled-assembly or external-editor route above. For controlled
   assembly, follow the [exact edit and completion procedure](../replay-director-workflow/references/production-camera-recovery.md#controlled-edit-and-final-check).
   Otherwise create or inspect one editor project and one primary sequence.
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

Contract review waits in `production_contract_draft` by default. **Submit** commits
comments for the current video and closes; **Approve plan** approves only the
requested project's current contract and closes. Other projects are excluded
from automatic review. Manual review lists pending plans only and can approve
those together. Approved contracts and their history remain saved.
Continue a timed-out wait via `production_status` with the returned
revision/sequence cursor, following the shared operations reference. Keep the
turn active while waiting; do not ask the player to send an extra chat message.
Escape discards unsent input and never approves.

Export review supports explicit **Reject and revise**, **Accept exceptions**, and
**Later**. Continue timed-out waits with the export binding/decision-sequence cursor
as documented in operations; keep the turn active while awaiting the player.
Later, Escape and AFK timeouts are not rejection or acceptance. A finished Codex
turn is not automatically awakened by this MCP wait.
