# Replay MCP live end-to-end test — 2026-09-18

## Outcome

The live Minecraft, Replay MCP, Replay Mod, and SynthCut workflow completed end
to end against the disposable Stonehill Castle world. The accepted delivery is:

- `run/replay_videos/stonehill-ascent-final.mp4`
- 30.200 seconds, H.264 video with AAC audio, 1280x720 at 30 fps
- 43,699,407 bytes
- SHA-256: `23f58bbc575556fe14d57dac506406411b901a914ed39fba8c4c3287d3119c92`

The finished sequence shows the player passing through the town, climbing the
constructed mountain stair route, walking into the castle entrance, and a
wide aerial orbit around the castle. The final export was sampled at 1.5, 4.2,
7.5, 10.5, 14.5, 17.5, 21.0, and 29.5 seconds. Those frames were visually
checked for story continuity, loaded chunks, camera placement, title treatment,
and absence of Replay Mod editor UI.

## Live setup and discovery

- Minecraft game directory: `/home/ofts/Replay MCP/run`
- Live bridge instance: `fedf55e6-dd85-4a20-9db4-0e821471f036`
- Live bridge port: `33401`
- Bridge protocol: `bridge/1`
- World: `Stonehill Castle`
- Minecraft: `26.2`
- Replay Mod: `26.2-2.6.27`

The sidecar initially needed an explicit custom game-directory configuration.
The working configuration was persisted with:

```sh
node ./plugins/replay-director/bin/replay-mcp-server.mjs configure \
  --game-dir "/home/ofts/Replay MCP/run"
```

`plugins/replay-director-template/LOCAL_TESTING.md` now documents this development setup,
including the sidecar restart requirement. No runtime source-code change was
needed during this live pass.

## Scene inspection and staging

The world was inspected before control or recording. The first exploratory take
was rejected and marked as a mistake because walls and the stream interrupted
the intended route. The disposable world was then staged through Replay MCP with:

- a stone lane through the town;
- a connector from the lane to the mountain;
- 31 ascending stone steps;
- a cleared ascent volume; and
- a level entry platform leading to the castle opening.

Post-staging observation:
`run/.replay-mcp/artifacts/observations/9fb87afc-d022-4be2-8486-0e4a4065d3f5.png`

## Accepted recording

- Logical take: `mountain-town-castle-360-e2e-take-02`
- Started: `2026-09-18T16:44:44.822894743Z`
- Stopped: `2026-09-18T16:54:49.323558811Z`
- Immutable native recording:
  `run/replay_recordings/recording/2026_09_18_11_48_11.mcpr`
- Recording size: 26,201,167 bytes
- Native replay duration: 2,520.472 seconds

Embedded replay markers, in replay milliseconds:

| Marker | Time |
| --- | ---: |
| take start | 1,656,769 |
| town | 1,721,916 |
| transition out of town | 1,739,723 |
| transition into mountain | 1,801,106 |
| mountain climb | 1,983,934 |
| transition out of mountain | 1,995,637 |
| transition into entry | 2,025,460 |
| castle entry | 2,075,756 |
| transition out of entry | 2,087,085 |
| transition into 360 | 2,140,476 |
| castle 360 | 2,245,884 |
| take end | 2,261,266 |

The replay was finalized only after returning the connected client to the title
screen. The original `.mcpr` remained immutable while working copies were edited.

## Replay Mod edit and review

The first 60-second native edit exposed unstable windows around fast replay-time
seeks: some frames showed unloaded chunks, foliage occlusion, or camera clipping.
Dense visual review identified clean source windows for the town, climb, entry,
and orbit. A short native master was then rebuilt from those windows, with a
settle interval before the orbit so chunks could load.

The retained native handoff is:

- edited replay: `run/replay_recordings/castle-e2e-replay-edit.mcpr`, 26,201,693
  bytes, SHA-256
  `4c6942eb97f878c1c8355d634c57dbe53ac14ea6dac1399a61dc396c5ddc9936`;
- native render: `run/replay_videos/castle-e2e-replay-edit.mp4`, H.264 yuv420p,
  1280x720 at 30 fps, 30.000 seconds, 24,151,657 bytes, SHA-256
  `3623409dc1e8bc2d6e275f9e5aefe059003bbf6dd8eb5ec91534887a50ca3e54`.

Frames from the actual native render were also checked across all four segments,
including five points around the complete, UI-free castle orbit.

One preview limitation was also found: Replay MCP contact-sheet sampling seeks
raw replay time instead of evaluating the authored output-time mapping. It can
therefore show the Replay Mod editor UI and is not authoritative for a rendered
timeline. Actual rendered video frames were used for acceptance instead.

## SynthCut AI-native edit

SynthCut imported the rendered Replay Mod footage and built a frame-accurate
non-destructive project at 1280x720/30 fps:

- project: `run/replay_videos/stonehill-ascent-final.aive`;
- four selected clips on V1: town, mountain climb, castle entry, and castle 360;
- eight-frame dissolves between story beats;
- subtle per-clip grade: contrast 1.04, saturation 1.05, gamma 1.01;
- 12-frame opening fade and 18-frame closing fade;
- restrained `STONEHILL ASCENT` opening title; and
- named timeline markers for all four requested beats.

The exact SynthCut preview was rendered before final export. The final MP4 was
then probed independently and visually sampled from the exported file, rather
than relying only on the editor timeline.

## Verification notes

- Replay MCP system discovery, observation, fenced director lease, world edits,
  character control, logical take markers, take stop, finalization, replay open,
  timeline application, and Replay Mod render were exercised live.
- The director lease was released after recording and is released again after
  the native edit/render workflow. The final sidecar status reported zero live
  instances after the client disconnected.
- A duplicate development-client launch was attempted while diagnosing discovery
  and immediately aborted before initialization. The already-running client
  remained the authoritative session.
- `npm run typecheck` passed.
- `npm test` passed all 13 tests in 6 files when run with localhost socket
  access. The restricted sandbox run was non-authoritative because it rejected
  test bridge binds to `127.0.0.1` with `EPERM`.
- `git diff --check` passed in the final repository handoff check.
