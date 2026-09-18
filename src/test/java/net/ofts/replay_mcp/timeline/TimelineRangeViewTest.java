package net.ofts.replay_mcp.timeline;

import com.replaymod.pathing.properties.TimestampProperty;
import com.replaymod.simplepathing.SPTimeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineRangeViewTest {
    @Test void shiftsOutputTimeWhilePreservingAuthoredReplayTimeEvaluation() {
        SPTimeline timeline = new SPTimeline();
        timeline.addTimeKeyframe(0, 10_000);
        timeline.addTimeKeyframe(10_000, 30_000);
        timeline.getTimePath().updateAll();

        TimelineRangeView range = new TimelineRangeView(timeline.getTimeline(), 2_000, 6_000);

        assertEquals(4_000, range.durationMs());
        assertEquals(14_000, range.getValue(TimestampProperty.PROPERTY, 0).orElseThrow());
        assertEquals(22_000, range.getValue(TimestampProperty.PROPERTY, 4_000).orElseThrow());
        assertEquals(4_000, range.getPaths().getFirst().getKeyframes().stream().mapToLong(k -> k.getTime()).max().orElseThrow());
    }
}
