package net.ofts.replay_mcp.timeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.protocol.CanonicalJson;
import net.ofts.replay_mcp.protocol.ProtocolLimits;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;

public final class TimelineEngine {
    private static final Set<String> NATIVE_TRACKS = Set.of(
            "replay_time", "camera_position", "yaw", "pitch", "roll", "spectated_entity", "markers");
    private static final Set<String> INTERPOLATIONS = Set.of("linear", "cubic_spline", "catmull_rom");
    private final ProtocolLimits limits;
    private final int undoLimit;
    private final Deque<Undo> undo = new ArrayDeque<>();
    private JsonObject timeline;

    private record Undo(String token, String expectedRevision, JsonObject state) { }

    public TimelineEngine(ProtocolLimits limits, int undoLimit) {
        this.limits = limits;
        this.undoLimit = undoLimit;
        timeline = new JsonObject();
        timeline.add("tracks", new JsonObject());
        timeline.add("markers", new JsonArray());
    }

    public synchronized JsonObject get() {
        JsonObject result = timeline.deepCopy();
        result.addProperty("revision", revision());
        result.addProperty("time_unit", "microseconds");
        result.addProperty("source_precision_us", 1_000);
        return result;
    }

    public synchronized JsonObject validate(JsonArray operations) {
        applyTo(timeline.deepCopy(), operations);
        JsonObject result = new JsonObject();
        result.addProperty("valid", true);
        result.add("normalized_operations", operations.deepCopy());
        return result;
    }

    public synchronized JsonObject apply(String baseRevision, JsonArray operations) {
        if (!revision().equals(baseRevision)) throw new BridgeException(BridgeError.CONFLICT, "timeline revision changed");
        JsonObject previous = timeline.deepCopy();
        JsonObject candidate = timeline.deepCopy();
        applyTo(candidate, operations);
        timeline = candidate;
        String token = UUID.randomUUID().toString();
        undo.addFirst(new Undo(token, revision(), previous));
        while (undo.size() > undoLimit) undo.removeLast();
        JsonObject result = get();
        result.addProperty("undo_token", token);
        result.add("warnings", new JsonArray());
        return result;
    }

    public synchronized JsonObject undo(String token, String baseRevision) {
        if (!revision().equals(baseRevision)) throw new BridgeException(BridgeError.CONFLICT, "timeline revision changed");
        Undo match = undo.stream().filter(u -> u.token().equals(token)).findFirst()
                .orElseThrow(() -> new BridgeException(BridgeError.CONFLICT, "undo token is stale or unknown"));
        if (!match.expectedRevision().equals(baseRevision)) throw new BridgeException(BridgeError.CONFLICT, "undo token is stale");
        timeline = match.state().deepCopy();
        undo.remove(match);
        return get();
    }

    private void applyTo(JsonObject target, JsonArray operations) {
        if (operations.isEmpty() || operations.size() > limits.maxTimelineOperations()) throw invalid("timeline operation count exceeds limit");
        for (JsonElement item : operations) {
            if (!item.isJsonObject()) throw invalid("timeline operation must be an object");
            JsonObject op = item.getAsJsonObject();
            String kind = requiredString(op, "op");
            switch (kind) {
                case "upsert_keyframe" -> upsert(target, op);
                case "delete_keyframe" -> delete(target, op);
                case "move_keyframe" -> move(target, op);
                case "set_interpolation" -> interpolation(target, op);
                case "upsert_marker" -> upsertMarker(target, op);
                case "delete_marker" -> deleteMarker(target, op);
                case "replace_track" -> replaceTrack(target, op);
                case "copy_transform" -> copyTransform(target, op);
                case "define_shot", "rename_shot", "reorder_shot", "remove_shot", "set_shot_range", "exclude_range" ->
                        throw unavailable("sidecar editorial tracks are not native Replay Mod tracks");
                default -> throw invalid("unknown timeline operation: " + kind);
            }
        }
    }

    private static void upsert(JsonObject target, JsonObject op) {
        JsonArray track = track(target, nativeTrack(op));
        long time = time(op, "time_us");
        JsonObject frame = requiredObject(op, "keyframe").deepCopy();
        frame.addProperty("time_us", normalizeTime(time));
        deleteAt(track, time);
        track.add(frame);
        sort(track);
    }

    private static void delete(JsonObject target, JsonObject op) {
        JsonArray track = track(target, nativeTrack(op));
        if (!deleteAt(track, time(op, "time_us"))) throw new BridgeException(BridgeError.CONFLICT, "keyframe does not exist");
    }

    private static void move(JsonObject target, JsonObject op) {
        JsonArray track = track(target, nativeTrack(op));
        long from = time(op, "from_us"), to = time(op, "to_us");
        JsonObject found = null;
        for (JsonElement element : track) if (element.getAsJsonObject().get("time_us").getAsLong() == normalizeTime(from)) found = element.getAsJsonObject();
        if (found == null) throw new BridgeException(BridgeError.CONFLICT, "keyframe does not exist");
        deleteAt(track, to);
        found.addProperty("time_us", normalizeTime(to));
        sort(track);
    }

    private static void interpolation(JsonObject target, JsonObject op) {
        String value = requiredString(op, "interpolation");
        if (!INTERPOLATIONS.contains(value)) throw invalid("unsupported interpolation");
        JsonArray track = track(target, nativeTrack(op));
        long at = normalizeTime(time(op, "time_us"));
        for (JsonElement element : track) {
            JsonObject frame = element.getAsJsonObject();
            if (frame.get("time_us").getAsLong() == at) { frame.addProperty("interpolation", value); return; }
        }
        throw new BridgeException(BridgeError.CONFLICT, "keyframe does not exist");
    }

    private static void upsertMarker(JsonObject target, JsonObject op) {
        String id = requiredString(op, "id");
        JsonArray markers = target.getAsJsonArray("markers");
        removeById(markers, id);
        JsonObject marker = op.deepCopy();
        marker.remove("op");
        marker.addProperty("time_us", normalizeTime(time(op, "time_us")));
        markers.add(marker);
    }

    private static void deleteMarker(JsonObject target, JsonObject op) {
        if (!removeById(target.getAsJsonArray("markers"), requiredString(op, "id"))) throw new BridgeException(BridgeError.CONFLICT, "marker does not exist");
    }

    private static void replaceTrack(JsonObject target, JsonObject op) {
        String name = nativeTrack(op);
        JsonArray replacement = op.getAsJsonArray("keyframes");
        if (replacement == null) throw invalid("keyframes array is required");
        JsonArray normalized = new JsonArray();
        for (JsonElement element : replacement) {
            JsonObject frame = element.getAsJsonObject().deepCopy();
            frame.addProperty("time_us", normalizeTime(time(frame, "time_us")));
            normalized.add(frame);
        }
        sort(normalized);
        target.getAsJsonObject("tracks").add(name, normalized);
    }

    private static void copyTransform(JsonObject target, JsonObject op) {
        JsonArray track = track(target, nativeTrack(op));
        long start = normalizeTime(time(op, "start_us")), end = normalizeTime(time(op, "end_us"));
        long offset = normalizeTime(time(op, "offset_us"));
        if (end < start) throw invalid("range end precedes start");
        JsonArray copies = new JsonArray();
        for (JsonElement element : track) {
            JsonObject frame = element.getAsJsonObject();
            long at = frame.get("time_us").getAsLong();
            if (at >= start && at <= end) {
                JsonObject copy = frame.deepCopy();
                copy.addProperty("time_us", Math.addExact(at, offset));
                copies.add(copy);
            }
        }
        copies.forEach(track::add);
        sort(track);
    }

    private static String nativeTrack(JsonObject op) {
        String track = requiredString(op, "track");
        if (Set.of("fov", "look_at", "shots", "excluded_ranges").contains(track)) throw unavailable(track + " is not a native Replay Mod track");
        if (!NATIVE_TRACKS.contains(track)) throw invalid("unknown native track: " + track);
        return track;
    }

    private static JsonArray track(JsonObject target, String name) {
        JsonObject tracks = target.getAsJsonObject("tracks");
        if (!tracks.has(name)) tracks.add(name, new JsonArray());
        return tracks.getAsJsonArray(name);
    }

    private static boolean deleteAt(JsonArray array, long time) {
        long normalized = normalizeTime(time);
        for (int i = 0; i < array.size(); i++) if (array.get(i).getAsJsonObject().get("time_us").getAsLong() == normalized) { array.remove(i); return true; }
        return false;
    }

    private static boolean removeById(JsonArray array, String id) {
        for (int i = 0; i < array.size(); i++) if (id.equals(array.get(i).getAsJsonObject().get("id").getAsString())) { array.remove(i); return true; }
        return false;
    }

    private static void sort(JsonArray array) {
        var values = new java.util.ArrayList<JsonElement>(); array.forEach(values::add);
        values.sort(java.util.Comparator.comparingLong(e -> e.getAsJsonObject().get("time_us").getAsLong()));
        while (!array.isEmpty()) array.remove(0); values.forEach(array::add);
    }

    private String revision() { return CanonicalJson.sha256(timeline); }
    public static long normalizeTime(long microseconds) { return Math.multiplyExact(Math.round(microseconds / 1000.0), 1000L); }
    private static long time(JsonObject object, String name) { if (!object.has(name)) throw invalid(name + " is required"); long time = object.get(name).getAsLong(); if (time < 0 && !name.equals("offset_us")) throw invalid(name + " cannot be negative"); return time; }
    private static String requiredString(JsonObject object, String name) { if (!object.has(name) || !object.get(name).isJsonPrimitive()) throw invalid(name + " is required"); return object.get(name).getAsString(); }
    private static JsonObject requiredObject(JsonObject object, String name) { if (!object.has(name) || !object.get(name).isJsonObject()) throw invalid(name + " object is required"); return object.getAsJsonObject(name); }
    private static BridgeException invalid(String message) { return new BridgeException(BridgeError.INVALID_REQUEST, message); }
    private static BridgeException unavailable(String message) { return new BridgeException(BridgeError.CAPABILITY_UNAVAILABLE, message); }
}
