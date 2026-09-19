# Replay MCP pathfinding live test — 2026-09-18

## Outcome

Ground `navigate_to` and corrected creative-flight controls were exercised in a
real Minecraft 26.2 session in the Stonehill Castle world. Short native routes,
typed unreachable-target rejection, creative takeoff/ascent, and uninterrupted
handoff across eight consecutive navigation actions were verified live.

The requested full route from `(-270, 119, 226)` down the road and into a house
was **not completed** before the test was paused. The longest uninterrupted
ground run reached approximately `(-341.51, 98, 276.24)`. Later scouting found
an open house doorway, but the ground evaluator still classified open doors as
blocked. The evaluator has now been corrected to pass already-open doors while
keeping closed doors blocked and never opening or otherwise modifying them.
That final doorway correction passed compilation and automated tests but has not
yet been re-verified in a live route.

## Verified live behavior

- A short round trip over uneven slabs completed with Minecraft-native paths of
  two and three nodes, zero replans, and grounded arrival within tolerance.
- An unreachable target returned `conflict` with `data.reason: no_path`.
- Consecutive `navigate_to` actions transfer directly to the next route in the
  same client tick. The eight-target descent had no intermediate input release
  or four-tick arrival settle; it stopped only when the next coarse target had
  no complete native path.
- Creative-flight activation followed the vanilla state transition: enable the
  granted ability, jump from the ground, and synchronize abilities. The player
  reported `flying: true` at Y=119.42, then a 20-tick ascent reached Y=127.34
  without horizontal drift.
- Flight-only actions now reject a non-flying state instead of degrading into
  ground jumping, walking, or sneaking.

## Live issues found and corrected

1. `EntityType#create` returns `null` for a `ClientLevel`; the detached planning
   proxy is now constructed as a vanilla zombie directly.
2. Arrival could be reported while jump momentum still carried the player; the
   last action now requires four grounded settle ticks.
3. Releasing inputs and settling between every queued waypoint made walking
   visibly disconnected; consecutive navigation or move actions now hand off
   without an intermediate stop.
4. Open door nodes were assigned a negative pathfinding malus and door passage
   was disabled. The planner now uses vanilla open-door passage while retaining
   `setCanOpenDoors(false)` and negative malus for both closed-door types.

## Evidence

- Short ground-path arrival:
  `run/.replay-mcp/artifacts/observations/9213e1a5-4587-4a9d-95c6-991feafe40f1.png`
- Verified creative flight:
  `run/.replay-mcp/artifacts/observations/4d2ce2fa-7e6b-4da8-bedf-2d4aaa830d78.png`
- Long continuous descent endpoint/failure scene:
  `run/.replay-mcp/artifacts/observations/ce5d5d81-45e5-4535-a008-bcc9a1367b17.png`

## Remaining live verification

- Re-run the complete uninterrupted road route after the open-door fix.
- Confirm final grounded arrival inside the house, beyond the open door plane.
- Exercise obstruction/replanning, cancellation, and human override as dedicated
  cases.

No blocks were broken, placed, or otherwise changed during this pathfinding
test. The final client restart reached bridge discovery, but the goal was paused
before reconnecting to the world and attempting the corrected full route.
