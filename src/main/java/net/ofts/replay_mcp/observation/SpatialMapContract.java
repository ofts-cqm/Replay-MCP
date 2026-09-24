package net.ofts.replay_mcp.observation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Language-neutral limits and validation for observation.spatial_map. */
public final class SpatialMapContract {
    public static final List<Integer> ORDINARY_CELL_SIZES = List.of(4, 8, 16, 32);
    public static final List<Integer> FALLBACK_CELL_SIZES = List.of(2);
    public static final Set<String> SURFACE_MODES = Set.of("world_surface", "motion_blocking_no_leaves");
    public static final Set<String> SURFACE_FALLBACK_REASONS = Set.of("unresolved_surface_boundary", "adjacent_surfaces");
    public static final Set<String> VOLUME_FALLBACK_REASONS = Set.of("tight_clearance", "thin_geometry", "adjacent_volumes");
    public static final long MAP_MAX_AGE_MS = 10 * 60 * 1_000L;

    public static final Limits ORDINARY_SURFACE = new Limits(4_096, 262_144, 2_048, 262_144);
    public static final Limits ORDINARY_VOLUME = new Limits(4_096, 2_097_152, 256, 262_144);
    public static final Limits FALLBACK_SURFACE = new Limits(512, 2_048, 64, 65_536);
    public static final Limits FALLBACK_VOLUME = new Limits(512, 4_096, 32, 65_536);

    private SpatialMapContract() {}

    public record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public long spanX() { return (long) maxX - minX; }
        public long spanY() { return (long) maxY - minY; }
        public long spanZ() { return (long) maxZ - minZ; }
        public boolean contains(Bounds other) {
            return minX <= other.minX && maxX >= other.maxX
                    && minY <= other.minY && maxY >= other.maxY
                    && minZ <= other.minZ && maxZ >= other.maxZ;
        }
        public JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("min_x", minX); result.addProperty("max_x", maxX);
            result.addProperty("min_y", minY); result.addProperty("max_y", maxY);
            result.addProperty("min_z", minZ); result.addProperty("max_z", maxZ);
            return result;
        }
    }

    public record Limits(int maxResultCells, long maxSourceWork, int maxAxisSpan, int maxResponseBytes) {}

    public record Request(String representation, Bounds requested, Bounds effective, int cellSize,
                          String surfaceMode, int materialMixLimit, boolean requireComplete,
                          String refinesMapId, String fallbackReason, Limits limits,
                          long aggregateCells, long sourceWork) {
        public boolean fallback() { return cellSize == 2; }
        public int gridX() { return Math.toIntExact(effective.spanX() / cellSize); }
        public int gridY() { return representation.equals("volume") ? Math.toIntExact(effective.spanY() / cellSize) : 1; }
        public int gridZ() { return Math.toIntExact(effective.spanZ() / cellSize); }
    }

    public static Request parse(JsonObject params, int minBuildY, int maxBuildY) {
        String representation = requiredEnum(params, "representation", Set.of("surface", "volume"));
        if (!params.has("bounds") || !params.get("bounds").isJsonObject()) invalid("bounds must be an object");
        JsonObject boundsJson = params.getAsJsonObject("bounds");
        Bounds requested = new Bounds(integer(boundsJson, "min_x"), integer(boundsJson, "max_x"),
                integer(boundsJson, "min_y"), integer(boundsJson, "max_y"),
                integer(boundsJson, "min_z"), integer(boundsJson, "max_z"));
        if (requested.minX >= requested.maxX || requested.minY >= requested.maxY || requested.minZ >= requested.maxZ) {
            invalid("bounds must be non-empty half-open intervals");
        }
        if (requested.minY < minBuildY || requested.maxY > maxBuildY) {
            JsonObject data = new JsonObject(); data.addProperty("min_build_y", minBuildY); data.addProperty("max_build_y", maxBuildY);
            throw new BridgeException(BridgeError.INVALID_REQUEST, "bounds are outside the active world's build height", data);
        }

        int cellSize = integer(params, "cell_size");
        if (!ORDINARY_CELL_SIZES.contains(cellSize) && !FALLBACK_CELL_SIZES.contains(cellSize)) {
            invalid("cell_size must be one of 2, 4, 8, 16, or 32");
        }
        String surfaceMode = representation.equals("surface")
                ? optionalEnum(params, "surface_mode", SURFACE_MODES, "motion_blocking_no_leaves") : null;
        if (representation.equals("volume") && params.has("surface_mode")) invalid("surface_mode is valid only for surface maps");
        int materialMixLimit = params.has("material_mix_limit") ? integer(params, "material_mix_limit") : 1;
        if (materialMixLimit < 1 || materialMixLimit > 3) invalid("material_mix_limit must be 1, 2, or 3");
        boolean requireComplete = optionalBoolean(params, "require_complete", false);
        String refinesMapId = optionalString(params, "refines_map_id");
        String fallbackReason = optionalString(params, "fallback_reason");
        if (cellSize == 2) {
            if (refinesMapId == null) invalid("refines_map_id is required for cell_size 2");
            Set<String> reasons = representation.equals("surface") ? SURFACE_FALLBACK_REASONS : VOLUME_FALLBACK_REASONS;
            if (fallbackReason == null || !reasons.contains(fallbackReason)) {
                invalid("fallback_reason is invalid for the selected representation");
            }
        } else if (refinesMapId != null || fallbackReason != null) {
            invalid("refines_map_id and fallback_reason are valid only for cell_size 2");
        }

        Bounds effective = align(requested, cellSize, true);
        if (representation.equals("volume") && (effective.minY < minBuildY || effective.maxY > maxBuildY)) {
            JsonObject data = new JsonObject(); data.add("requested_bounds", requested.json()); data.add("effective_bounds", effective.json());
            data.addProperty("min_build_y", minBuildY); data.addProperty("max_build_y", maxBuildY);
            throw new BridgeException(BridgeError.INVALID_REQUEST, "aligned volume bounds exceed the active world's build height", data);
        }

        boolean fallback = cellSize == 2;
        Limits limits = representation.equals("surface")
                ? (fallback ? FALLBACK_SURFACE : ORDINARY_SURFACE)
                : (fallback ? FALLBACK_VOLUME : ORDINARY_VOLUME);
        long aggregateCells = multiply(effective.spanX() / cellSize, effective.spanZ() / cellSize);
        if (representation.equals("volume")) aggregateCells = multiply(aggregateCells, effective.spanY() / cellSize);
        long sourceWork = multiply(effective.spanX(), effective.spanZ());
        if (representation.equals("volume")) sourceWork = multiply(sourceWork, effective.spanY());
        long maxSpan = representation.equals("surface")
                ? Math.max(effective.spanX(), effective.spanZ())
                : Math.max(effective.spanX(), Math.max(effective.spanY(), effective.spanZ()));
        if (aggregateCells > limits.maxResultCells || sourceWork > limits.maxSourceWork || maxSpan > limits.maxAxisSpan) {
            JsonObject data = new JsonObject();
            data.addProperty("reason", "query_too_large");
            data.addProperty("aggregate_cells", aggregateCells); data.addProperty("max_aggregate_cells", limits.maxResultCells);
            data.addProperty(representation.equals("surface") ? "source_columns" : "logical_voxels", sourceWork);
            data.addProperty("max_source_work", limits.maxSourceWork); data.addProperty("max_axis_span", limits.maxAxisSpan);
            data.add("effective_bounds", effective.json());
            int suggested = nextCellSize(cellSize);
            if (suggested > cellSize) data.addProperty("suggested_cell_size", suggested);
            data.addProperty("suggestion", representation.equals("surface")
                    ? "use a larger cell_size or tile the X/Z bounds"
                    : "use a larger cell_size, tile the bounds, or reduce the Y range");
            throw new BridgeException(BridgeError.QUERY_TOO_LARGE, "spatial map exceeds the negotiated work limits", data);
        }
        return new Request(representation, requested, effective, cellSize, surfaceMode, materialMixLimit,
                requireComplete, refinesMapId, fallbackReason, limits, aggregateCells, sourceWork);
    }

    public static JsonObject capability() {
        JsonObject result = new JsonObject();
        result.addProperty("surface", true); result.addProperty("volume", true); result.addProperty("loaded_chunks_only", true);
        result.add("surface_cell_sizes", integers(ORDINARY_CELL_SIZES)); result.add("surface_fallback_cell_sizes", integers(FALLBACK_CELL_SIZES));
        result.add("volume_cell_sizes", integers(ORDINARY_CELL_SIZES)); result.add("volume_fallback_cell_sizes", integers(FALLBACK_CELL_SIZES));
        JsonArray modes = new JsonArray(); modes.add("world_surface"); modes.add("motion_blocking_no_leaves"); result.add("surface_modes", modes);
        result.addProperty("default_surface_mode", "motion_blocking_no_leaves"); result.add("material_mix_limits", integers(List.of(1, 2, 3)));
        result.addProperty("ordinary_target_cells", 1_024); result.addProperty("ordinary_max_result_cells", 4_096);
        result.addProperty("fallback_max_result_cells", 512); result.addProperty("max_response_bytes", 262_144);
        result.addProperty("fallback_max_response_bytes", 65_536); result.addProperty("partial_coverage", true);
        result.addProperty("multi_tick", true); result.addProperty("max_map_age_ms", MAP_MAX_AGE_MS);
        JsonObject limits = new JsonObject();
        limits.add("ordinary_surface", limits(ORDINARY_SURFACE, "max_source_columns"));
        limits.add("ordinary_volume", limits(ORDINARY_VOLUME, "max_logical_voxels"));
        limits.add("surface_fallback", limits(FALLBACK_SURFACE, "max_source_columns"));
        limits.add("volume_fallback", limits(FALLBACK_VOLUME, "max_logical_voxels"));
        result.add("limits", limits);
        return result;
    }

    public static Bounds align(Bounds requested, int cellSize, boolean alignY) {
        long minX = Math.floorDiv((long) requested.minX, cellSize) * cellSize;
        long maxX = ceilMultiple(requested.maxX, cellSize);
        long minY = alignY ? Math.floorDiv((long) requested.minY, cellSize) * cellSize : requested.minY;
        long maxY = alignY ? ceilMultiple(requested.maxY, cellSize) : requested.maxY;
        long minZ = Math.floorDiv((long) requested.minZ, cellSize) * cellSize;
        long maxZ = ceilMultiple(requested.maxZ, cellSize);
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE || minY < Integer.MIN_VALUE || maxY > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) invalid("aligned bounds exceed integer world coordinates");
        return new Bounds((int) minX, (int) maxX, (int) minY, (int) maxY, (int) minZ, (int) maxZ);
    }

    private static JsonObject limits(Limits limits, String workName) {
        JsonObject result = new JsonObject(); result.addProperty("max_result_cells", limits.maxResultCells);
        result.addProperty(workName, limits.maxSourceWork); result.addProperty("max_axis_span", limits.maxAxisSpan);
        result.addProperty("max_response_bytes", limits.maxResponseBytes); return result;
    }

    private static JsonArray integers(List<Integer> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private static long ceilMultiple(int value, int cellSize) { return -Math.floorDiv(-(long) value, cellSize) * cellSize; }
    private static long multiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static int nextCellSize(int size) { return size < 4 ? 4 : size < 8 ? 8 : size < 16 ? 16 : size < 32 ? 32 : 32; }

    private static int integer(JsonObject object, String key) {
        if (!object.has(key) || !(object.get(key) instanceof JsonPrimitive primitive) || !primitive.isNumber()) invalid(key + " must be an integer");
        try {
            BigDecimal value = object.get(key).getAsBigDecimal();
            if (value.stripTrailingZeros().scale() > 0) invalid(key + " must be an integer");
            return value.intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) { invalid(key + " must be a 32-bit integer"); return 0; }
    }
    private static String requiredEnum(JsonObject object, String key, Set<String> values) {
        String value = optionalString(object, key); if (value == null || !values.contains(value)) invalid(key + " is invalid"); return value;
    }
    private static String optionalEnum(JsonObject object, String key, Set<String> values, String fallback) {
        String value = optionalString(object, key); if (value == null) return fallback; if (!values.contains(value)) invalid(key + " is invalid"); return value;
    }
    private static String optionalString(JsonObject object, String key) {
        if (!object.has(key)) return null;
        if (!(object.get(key) instanceof JsonPrimitive) || !object.getAsJsonPrimitive(key).isString()
                || object.get(key).getAsString().isBlank()) invalid(key + " must be a non-empty string");
        JsonPrimitive primitive = object.getAsJsonPrimitive(key);
        return primitive.getAsString();
    }
    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        if (!(object.get(key) instanceof JsonPrimitive) || !object.getAsJsonPrimitive(key).isBoolean()) invalid(key + " must be a boolean");
        JsonPrimitive primitive = object.getAsJsonPrimitive(key);
        return primitive.getAsBoolean();
    }
    private static void invalid(String message) { throw new BridgeException(BridgeError.INVALID_REQUEST, message); }
}
