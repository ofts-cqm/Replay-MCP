package net.ofts.replay_mcp.production;

import com.replaymod.pathing.properties.TimestampProperty;
import com.replaymod.replaystudio.pathing.interpolation.LinearInterpolator;
import com.replaymod.simplepathing.SPTimeline;

/** Eligibility uses the same native paths consumed by Replay Mod rendering. */
public final class NativeClearancePolicy {
    private NativeClearancePolicy() { }
    /** Validate identity against refreshed native interpolation, including immediately rebuilt paths. */
    public static boolean followBindingMatches(SPTimeline timeline, long start, long end,
            com.google.gson.JsonObject trace, com.google.gson.JsonObject params, String replaySession) {
        String unsupported = unsupportedReason(timeline, start, end, true);
        // Eligibility updates the interpolators. Reading before it can cache an absent (-1) source.
        int source = timeline.getTimePath().getValue(TimestampProperty.PROPERTY, start).orElse(-1);
        return trace != null && unsupported == null && start == 0
                && trace.get("player_uuid").equals(params.get("follow_player_uuid"))
                && trace.get("follow_fps").equals(params.get("follow_fps"))
                && trace.get("replay_session").getAsString().equals(replaySession)
                && trace.get("source_start_us").getAsLong() == source * 1000L
                && trace.get("source_end_us").getAsLong() == (source + end) * 1000L;
    }
    public static String unsupportedReason(SPTimeline timeline, long start, long end) {
        return unsupportedReason(timeline,start,end,false);
    }
    public static String unsupportedReason(SPTimeline timeline, long start, long end, boolean advancing) {
        var time = timeline.getTimePath();
        var position = timeline.getPositionPath();
        timeline.getTimeline().getPaths().forEach(com.replaymod.replaystudio.pathing.path.Path::updateAll);
        for (var path : java.util.List.of(time, position)) {
            if (path.getKeyframes().isEmpty() || path.getKeyframes().stream().mapToLong(k -> k.getTime()).min().orElseThrow() > start
                    || path.getKeyframes().stream().mapToLong(k -> k.getTime()).max().orElseThrow() < end)
                return "native camera/time keys do not cover requested range";
            for (var segment : path.getSegments())
                if (segment.getEndKeyframe().getTime() > start && segment.getStartKeyframe().getTime() < end
                        && !(segment.getInterpolator() instanceof LinearInterpolator))
                    return "curved, spectator or nonlinear time interpolation has no conservative bound; use linear free-camera keys";
        }
        if (advancing) {
            Integer source = time.getValue(TimestampProperty.PROPERTY,start).orElse(null);
            if(source==null) return "missing source mapping";
            java.util.TreeSet<Long> checks = new java.util.TreeSet<>(); checks.add(start); checks.add(end);
            for(var key:time.getKeyframes()) if(key.getTime()>start && key.getTime()<end) checks.add(key.getTime());
            for(long t:checks) if(time.getValue(TimestampProperty.PROPERTY,t).orElse(-1)!=(long)source+t-start)
                return "temporal policy supports only normal advance_1x";
            return null;
        }
        Integer frozen = time.getValue(TimestampProperty.PROPERTY, start).orElse(null);
        if (frozen == null || !time.getValue(TimestampProperty.PROPERTY, end).orElse(-1).equals(frozen))
            return "advancing replay geometry history is unsupported; use authorized freeze or explicit collision skip";
        for (var key : time.getKeyframes())
            if (key.getTime() > start && key.getTime() < end && !key.getValue(TimestampProperty.PROPERTY).orElse(-1).equals(frozen))
                return "changing replay-time mapping is unsupported; equal endpoints do not establish frozen geometry";
        return null;
    }
}
