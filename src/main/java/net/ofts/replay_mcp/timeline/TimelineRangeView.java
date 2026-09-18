package net.ofts.replay_mcp.timeline;

import com.replaymod.replaystudio.pathing.change.Change;
import com.replaymod.replaystudio.pathing.path.Keyframe;
import com.replaymod.replaystudio.pathing.path.Path;
import com.replaymod.replaystudio.pathing.path.PathSegment;
import com.replaymod.replaystudio.pathing.path.Timeline;
import com.replaymod.replaystudio.pathing.property.Property;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only output-time window over an authored Replay Mod timeline. */
public final class TimelineRangeView implements Timeline {
    private final Timeline delegate;
    private final long startMs;
    private final long durationMs;
    private final List<Path> paths;

    public TimelineRangeView(Timeline delegate, long startMs, long endMs) {
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid timeline range");
        this.delegate = delegate;
        this.startMs = startMs;
        this.durationMs = endMs - startMs;
        this.paths = delegate.getPaths().stream().map(RangePath::new).map(Path.class::cast).toList();
    }

    public long sourceTime(long outputMs) { return startMs + clamp(outputMs); }
    public long durationMs() { return durationMs; }

    @Override public List<Path> getPaths() { return paths; }
    @Override public <T> Optional<T> getValue(Property<T> property, long time) { return delegate.getValue(property, sourceTime(time)); }
    @Override public void applyToGame(long time, Object replayHandler) { delegate.applyToGame(sourceTime(time), replayHandler); }
    @Override public Property getProperty(String id) { return delegate.getProperty(id); }

    @Override public Path createPath() { throw readOnly(); }
    @Override public void registerProperty(Property property) { throw readOnly(); }
    @Override public void applyChange(Change change) { throw readOnly(); }
    @Override public void pushChange(Change change) { throw readOnly(); }
    @Override public void undoLastChange() { throw readOnly(); }
    @Override public void redoLastChange() { throw readOnly(); }
    @Override public Change peekUndoStack() { return null; }
    @Override public Change peekRedoStack() { return null; }

    private long clamp(long time) { return Math.max(0, Math.min(durationMs, time)); }
    private static UnsupportedOperationException readOnly() { return new UnsupportedOperationException("timeline range view is read-only"); }

    private final class RangePath implements Path {
        private final Path path;
        private final Collection<Keyframe> keyframes;

        private RangePath(Path path) {
            this.path = path;
            Set<Long> times = new LinkedHashSet<>();
            times.add(0L);
            path.getKeyframes().stream().map(Keyframe::getTime)
                    .filter(time -> time > startMs && time < startMs + durationMs)
                    .map(time -> time - startMs).forEach(times::add);
            times.add(durationMs);
            keyframes = times.stream().map(time -> new RangeKeyframe(path, time, startMs + time)).map(Keyframe.class::cast).toList();
        }

        @Override public Timeline getTimeline() { return TimelineRangeView.this; }
        @Override public Collection<Keyframe> getKeyframes() { return keyframes; }
        @Override public Collection<PathSegment> getSegments() { return List.of(); }
        @Override public void update() { path.update(); }
        @Override public void updateAll() { path.updateAll(); }
        @Override public <T> Optional<T> getValue(Property<T> property, long time) { return path.getValue(property, sourceTime(time)); }
        @Override public Keyframe getKeyframe(long time) { return keyframes.stream().filter(keyframe -> keyframe.getTime() == time).findFirst().orElse(null); }
        @Override public boolean isActive() { return path.isActive(); }
        @Override public Keyframe insert(long time) { throw readOnly(); }
        @Override public void insert(Keyframe keyframe) { throw readOnly(); }
        @Override public void remove(Keyframe keyframe, boolean useFirst) { throw readOnly(); }
        @Override public void setActive(boolean active) { throw readOnly(); }
    }

    private record RangeKeyframe(Path path, long outputTime, long sourceTime) implements Keyframe {
        @Override public long getTime() { return outputTime; }
        @Override public <T> Optional<T> getValue(Property<T> property) { return path.getValue(property, sourceTime); }
        @Override public Set<Property> getProperties() { return Set.of(); }
        @Override public <T> void setValue(Property<T> property, T value) { throw readOnly(); }
        @Override public void removeProperty(Property property) { throw readOnly(); }
    }
}
