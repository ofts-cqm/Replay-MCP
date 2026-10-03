package net.ofts.replay_mcp.timeline;

import com.replaymod.pathing.properties.TimestampProperty;
import com.replaymod.simplepathing.SPTimeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineRangeViewTest {
    @Test void stillAtFinalEndpointHasOneFrameWithoutExtrapolatingSourceTime() {
        SPTimeline timeline = new SPTimeline();
        timeline.addTimeKeyframe(0, 1000);
        timeline.addTimeKeyframe(3000, 4000);
        timeline.getTimePath().updateAll();
        TimelineRangeView still = TimelineRangeView.still(timeline.getTimeline(), 3000);
        assertEquals(100, still.durationMs());
        assertEquals(3000, still.sourceTime(0));
        assertEquals(3000, still.sourceTime(100));
        assertEquals(4000, still.getValue(TimestampProperty.PROPERTY, 100).orElseThrow());
        var path = still.getPaths().stream().filter(p -> p.getValue(TimestampProperty.PROPERTY, 0).isPresent()).findFirst().orElseThrow();
        assertEquals(100, path.getKeyframes().stream().mapToLong(k -> k.getTime()).max().orElseThrow());
        assertEquals(4000, path.getKeyframe(100).getValue(TimestampProperty.PROPERTY).orElseThrow());
    }

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
