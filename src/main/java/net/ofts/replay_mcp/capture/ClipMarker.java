package net.ofts.replay_mcp.capture;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable, private Replay Mod marker grammar shared by player and agent capture. */
public record ClipMarker(String clipId, Kind kind) {
    public static final String PREFIX = "replay_mcp:clip:v1:";
    private static final Pattern PATTERN = Pattern.compile(
            "^replay_mcp:clip:v1:([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}):(start|end|revoke)$",
            Pattern.CASE_INSENSITIVE);

    public enum Kind { START, END, REVOKE }

    public ClipMarker {
        clipId = UUID.fromString(clipId).toString();
    }

    public String markerName() {
        return PREFIX + clipId + ":" + kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Optional<ClipMarker> parse(String name) {
        if (name == null) return Optional.empty();
        Matcher matcher = PATTERN.matcher(name);
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(new ClipMarker(matcher.group(1), Kind.valueOf(matcher.group(2).toUpperCase(java.util.Locale.ROOT))));
    }
}
