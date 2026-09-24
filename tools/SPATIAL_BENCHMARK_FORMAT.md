# Spatial observation benchmark input

`evaluate_spatial_benchmark.py` evaluates the product gates in
`SPATIAL_OBSERVATION_SCOPE.md`; it does not run or prompt an agent. The trial
harness writes one JSON object per line after each attempt. Each scenario and
condition needs at least ten fresh-context trials.

Required identity fields are `scenario`, `scenario_class`, `trial_id`,
`pair_id`, `condition`, `model`, `settings_id`, and `world_state_id`.
`condition` is `baseline`, `tool_only`, or `complete_change`. The three records
in a pair use identical model/settings/world-state values. Supported scenario
classes include `surface`, `ordinary_exterior`, `ordinary_terrain`,
`volume_required`, `fine_surface`, and `tight_volume`.

Required measured fields are:

- `accepted`, `visual_accepted`, and `collision_range_valid` booleans;
- `wall_time_ms`, `screenshot_count`, `surface_calls`, `volume_calls`,
  `surface_fallback_calls`, and `volume_fallback_calls`;
- `input_tokens`, `output_tokens`, and `cached_tokens` as non-negative integers
  or `null` when the runtime does not expose them;
- `usage_includes_vision`, which is true only when `input_tokens` includes the
  visual input accounted by the runtime; and
- optional `fallback_resolved` for the dedicated size-2 fixtures.

Example:

```json
{"scenario":"overworld_house","scenario_class":"ordinary_exterior","trial_id":"complete-01","pair_id":"pair-01","condition":"complete_change","model":"example-model","settings_id":"settings-v1","world_state_id":"house-state-01","accepted":true,"visual_accepted":true,"collision_range_valid":true,"wall_time_ms":42000,"screenshot_count":2,"surface_calls":2,"volume_calls":0,"surface_fallback_calls":0,"volume_fallback_calls":0,"input_tokens":18000,"output_tokens":2500,"cached_tokens":0,"usage_includes_vision":true,"fallback_resolved":null}
```

Run:

```sh
python3 tools/evaluate_spatial_benchmark.py trials.jsonl --output report.json
```

Exit status is zero only when every gate passes. A missing or non-vision-aware
token measurement leaves the token gate `null` and prevents an overall pass;
the evaluator never substitutes response bytes or screenshot counts for exact
token telemetry.
