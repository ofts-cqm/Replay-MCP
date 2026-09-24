#!/usr/bin/env python3
"""Evaluate Replay MCP spatial-observation agent trials without inventing token telemetry."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

CONDITIONS = ("baseline", "tool_only", "complete_change")
ORDINARY_CLASSES = {"surface", "ordinary_exterior", "ordinary_terrain"}
DEDICATED_CLASSES = {"fine_surface", "tight_volume"}


def load_trials(path: Path) -> list[dict[str, Any]]:
    trials: list[dict[str, Any]] = []
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        try:
            trial = json.loads(raw)
        except json.JSONDecodeError as failure:
            raise ValueError(f"line {line_number}: invalid JSON: {failure.msg}") from failure
        validate_trial(trial, line_number)
        trials.append(trial)
    if not trials:
        raise ValueError("benchmark input contains no trials")
    return trials


def validate_trial(trial: Any, line_number: int) -> None:
    if not isinstance(trial, dict):
        raise ValueError(f"line {line_number}: trial must be an object")
    for key in ("scenario", "scenario_class", "trial_id", "pair_id", "condition", "model", "settings_id", "world_state_id"):
        if not isinstance(trial.get(key), str) or not trial[key]:
            raise ValueError(f"line {line_number}: {key} must be a non-empty string")
    if trial["condition"] not in CONDITIONS:
        raise ValueError(f"line {line_number}: unknown condition {trial['condition']}")
    for key in ("accepted", "visual_accepted", "collision_range_valid", "usage_includes_vision"):
        if not isinstance(trial.get(key), bool):
            raise ValueError(f"line {line_number}: {key} must be boolean")
    for key in ("wall_time_ms", "screenshot_count", "surface_calls", "volume_calls", "surface_fallback_calls", "volume_fallback_calls"):
        if not isinstance(trial.get(key), (int, float)) or trial[key] < 0:
            raise ValueError(f"line {line_number}: {key} must be non-negative")
    for key in ("input_tokens", "output_tokens", "cached_tokens"):
        if trial.get(key) is not None and (not isinstance(trial[key], int) or trial[key] < 0):
            raise ValueError(f"line {line_number}: {key} must be a non-negative integer or null")
    if trial.get("fallback_resolved") is not None and not isinstance(trial["fallback_resolved"], bool):
        raise ValueError(f"line {line_number}: fallback_resolved must be boolean or null")


def metric_per_success(trials: list[dict[str, Any]], key: str) -> float | None:
    accepted = sum(1 for trial in trials if trial["accepted"])
    if accepted == 0 or any(trial.get(key) is None for trial in trials):
        return None
    return sum(float(trial[key]) for trial in trials) / accepted


def rate(trials: list[dict[str, Any]], key: str) -> float:
    return sum(1 for trial in trials if trial[key]) / len(trials)


def reduction(baseline: float | None, changed: float | None) -> float | None:
    if baseline is None or changed is None or baseline == 0:
        return None
    return 1 - changed / baseline


def evaluate(trials: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    scenarios: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    pairs: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for trial in trials:
        grouped[trial["condition"]].append(trial)
        scenarios[(trial["scenario"], trial["condition"])].append(trial)
        pairs[(trial["scenario"], trial["pair_id"])].append(trial)

    pairing_errors: list[str] = []
    for (scenario, pair_id), pair in pairs.items():
        if {trial["condition"] for trial in pair} != set(CONDITIONS):
            pairing_errors.append(f"{scenario}/{pair_id}: missing benchmark condition")
            continue
        for key in ("model", "settings_id", "world_state_id"):
            if len({trial[key] for trial in pair}) != 1:
                pairing_errors.append(f"{scenario}/{pair_id}: {key} differs across conditions")

    trial_count_errors = [
        f"{scenario}/{condition}: {len(items)} trials; at least 10 required"
        for (scenario, condition), items in sorted(scenarios.items()) if len(items) < 10
    ]
    missing_conditions = [condition for condition in CONDITIONS if not grouped[condition]]

    summaries: dict[str, dict[str, Any]] = {}
    for condition in CONDITIONS:
        items = grouped[condition]
        if not items:
            summaries[condition] = {"trials": 0}
            continue
        token_telemetry = all(trial["input_tokens"] is not None and trial["usage_includes_vision"] for trial in items)
        summaries[condition] = {
            "trials": len(items),
            "accepted_shot_rate": rate(items, "accepted"),
            "blinded_visual_acceptance_rate": rate(items, "visual_accepted"),
            "collision_range_valid_rate": rate(items, "collision_range_valid"),
            "input_tokens_per_accepted_shot": metric_per_success(items, "input_tokens") if token_telemetry else None,
            "wall_time_ms_per_accepted_shot": metric_per_success(items, "wall_time_ms"),
            "screenshots_per_accepted_shot": metric_per_success(items, "screenshot_count"),
            "token_gate_evaluable": token_telemetry,
        }

    baseline, changed = summaries["baseline"], summaries["complete_change"]
    token_reduction = reduction(baseline.get("input_tokens_per_accepted_shot"), changed.get("input_tokens_per_accepted_shot"))
    time_reduction = reduction(baseline.get("wall_time_ms_per_accepted_shot"), changed.get("wall_time_ms_per_accepted_shot"))
    screenshot_reduction = reduction(baseline.get("screenshots_per_accepted_shot"), changed.get("screenshots_per_accepted_shot"))
    ordinary = [trial for trial in grouped["complete_change"] if trial["scenario_class"] in ORDINARY_CLASSES]
    fallback_rate = (sum(1 for trial in ordinary if trial["surface_fallback_calls"] + trial["volume_fallback_calls"] > 0) / len(ordinary)) if ordinary else None
    dedicated = {
        scenario_class: any(
            trial["accepted"] and trial.get("fallback_resolved") is True
            and trial["surface_fallback_calls"] + trial["volume_fallback_calls"] > 0
            for trial in grouped["complete_change"] if trial["scenario_class"] == scenario_class
        ) for scenario_class in DEDICATED_CLASSES
    }

    gates = {
        "paired_identical_conditions": not pairing_errors,
        "at_least_ten_trials_per_scenario_condition": not trial_count_errors and not missing_conditions,
        "accepted_shot_regression_at_most_5_points": bool(baseline.get("trials") and changed.get("trials")) and changed["accepted_shot_rate"] >= baseline["accepted_shot_rate"] - 0.05,
        "visual_acceptance_regression_at_most_5_points": bool(baseline.get("trials") and changed.get("trials")) and changed["blinded_visual_acceptance_rate"] >= baseline["blinded_visual_acceptance_rate"] - 0.05,
        "input_tokens_reduced_at_least_30_percent": None if token_reduction is None else token_reduction >= 0.30,
        "wall_time_reduced_at_least_25_percent": None if time_reduction is None else time_reduction >= 0.25,
        "screenshots_reduced_at_least_50_percent": None if screenshot_reduction is None else screenshot_reduction >= 0.50,
        "ordinary_level_2_fallback_rate_at_most_10_percent": None if fallback_rate is None else fallback_rate <= 0.10,
        "dedicated_level_2_fixtures_resolved": all(dedicated.values()),
    }
    passed = all(value is True for value in gates.values())
    return {
        "passed": passed,
        "note": "A null token gate means vision-inclusive runtime usage telemetry was unavailable; token savings are not established.",
        "summaries": summaries,
        "comparisons": {
            "input_token_reduction": token_reduction,
            "wall_time_reduction": time_reduction,
            "screenshot_reduction": screenshot_reduction,
            "ordinary_level_2_fallback_rate": fallback_rate,
            "dedicated_fallback_resolution": dedicated,
        },
        "gates": gates,
        "pairing_errors": pairing_errors,
        "trial_count_errors": trial_count_errors,
        "missing_conditions": missing_conditions,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate JSONL trials for the SPATIAL_OBSERVATION_SCOPE.md product gates.")
    parser.add_argument("trials", type=Path, help="JSONL file with one harness-recorded trial per line")
    parser.add_argument("--output", type=Path, help="optional path for the JSON report")
    arguments = parser.parse_args()
    try:
        report = evaluate(load_trials(arguments.trials))
    except (OSError, ValueError) as failure:
        parser.error(str(failure))
    rendered = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
    if arguments.output:
        arguments.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
