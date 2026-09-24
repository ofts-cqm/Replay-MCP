package net.ofts.replay_mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.ofts.replay_mcp.observation.SpatialMapContract;
import net.ofts.replay_mcp.operation.CancellationToken;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Time-sliced, loaded-client-chunk-only implementation of observation.spatial_map. */
final class SpatialMapSampler implements AutoCloseable {
    private static final long TARGET_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long HARD_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

    private final Minecraft minecraft;
    private final ArrayDeque<Task> pending = new ArrayDeque<>();
    private final ExecutorService assembler = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "replay-mcp-spatial-map-assembler");
        thread.setDaemon(true); return thread;
    });

    SpatialMapSampler(Minecraft minecraft) { this.minecraft = minecraft; }

    CompletableFuture<JsonObject> submit(JsonObject params, CancellationToken cancellation,
                                         String runtimeMode, Long replayTimeUs, Runnable stateGuard) {
        if (!minecraft.isSameThread()) throw new IllegalStateException("spatial map submission must run on the client thread");
        ClientLevel level = minecraft.level;
        if (level == null) throw new BridgeException(BridgeError.INVALID_MODE, "no active client world");
        // Minecraft exposes getMaxY() as the greatest valid block coordinate,
        // while the spatial contract uses half-open bounds on every axis.
        SpatialMapContract.Request request = SpatialMapContract.parse(params, level.getMinY(), level.getMaxY() + 1);
        long deadlineMs = params.has("deadline_ms") ? params.get("deadline_ms").getAsLong() : 30_000L;
        Task task = request.representation().equals("surface")
                ? new SurfaceTask(level, request, cancellation, runtimeMode, replayTimeUs, deadlineMs, stateGuard)
                : new VolumeTask(level, request, cancellation, runtimeMode, replayTimeUs, deadlineMs, stateGuard);
        pending.add(task);
        return task.future;
    }

    JsonObject await(CompletableFuture<JsonObject> future, CancellationToken cancellation, long deadlineMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(250L, deadlineMs));
        while (true) {
            try { return future.get(Math.min(100L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))), TimeUnit.MILLISECONDS); }
            catch (TimeoutException polling) {
                if (cancellation.isCancelled()) throw new BridgeException(BridgeError.CANCELLED, "spatial map was cancelled");
                if (System.nanoTime() >= deadline) { cancellation.cancel(); throw new BridgeException(BridgeError.TIMEOUT, "spatial map deadline exceeded"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); cancellation.cancel(); throw new BridgeException(BridgeError.CANCELLED, "spatial map was interrupted");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof BridgeException bridge) throw bridge;
                throw new BridgeException(BridgeError.INTERNAL_ERROR, "spatial map assembly failed");
            }
        }
    }

    void tick() {
        if (!minecraft.isSameThread()) throw new IllegalStateException("spatial map tick must run on the client thread");
        Task task = pending.peek();
        if (task == null) return;
        long started = System.nanoTime();
        try {
            task.checkState();
            while (!task.complete() && System.nanoTime() - started < TARGET_SLICE_NANOS) task.step();
            long elapsed = System.nanoTime() - started;
            task.recordSlice(elapsed);
            if (elapsed > HARD_SLICE_NANOS) task.hardSliceOverruns++;
            if (!task.complete()) return;
            pending.remove();
            task.captureEndTick = task.level.getGameTime();
            CompletableFuture.supplyAsync(task::assemble, assembler).whenComplete((result, failure) -> {
                if (failure == null) task.future.complete(result);
                else task.future.completeExceptionally(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure);
            });
        } catch (Throwable failure) {
            pending.remove(task);
            task.future.completeExceptionally(failure);
        }
    }

    @Override public void close() {
        BridgeException closed = new BridgeException(BridgeError.CANCELLED, "spatial sampler closed");
        pending.forEach(task -> task.future.completeExceptionally(closed)); pending.clear(); assembler.shutdownNow();
    }

    private abstract class Task {
        final ClientLevel level;
        final SpatialMapContract.Request request;
        final CancellationToken cancellation;
        final String runtimeMode;
        final Long replayTimeUs;
        final Runnable stateGuard;
        final long deadlineNanos;
        final long captureStartTick;
        final String dimension;
        final CompletableFuture<JsonObject> future = new CompletableFuture<>();
        final Map<Long, LevelChunk> loadedChunks = new HashMap<>();
        final Set<Long> unavailableChunks = new HashSet<>();
        long captureEndTick;
        long loadedUnits;
        long unavailableUnits;
        long clientThreadNanos;
        long maxSliceNanos;
        int scanSlices;
        int hardSliceOverruns;

        Task(ClientLevel level, SpatialMapContract.Request request, CancellationToken cancellation,
             String runtimeMode, Long replayTimeUs, long deadlineMs, Runnable stateGuard) {
            this.level = level; this.request = request; this.cancellation = cancellation;
            this.runtimeMode = runtimeMode; this.replayTimeUs = replayTimeUs;
            this.stateGuard = stateGuard;
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(250L, Math.min(300_000L, deadlineMs)));
            this.captureStartTick = level.getGameTime(); this.dimension = level.dimension().identifier().toString();
        }

        abstract void step();
        abstract boolean complete();
        abstract JsonObject encode();

        void checkState() {
            cancellation.throwIfCancelled();
            stateGuard.run();
            if (System.nanoTime() >= deadlineNanos) throw new BridgeException(BridgeError.TIMEOUT, "spatial map deadline exceeded");
            if (minecraft.level != level || !minecraft.level.dimension().identifier().toString().equals(dimension)) {
                throw new BridgeException(BridgeError.CONFLICT, "active world changed during spatial sampling");
            }
        }

        LevelChunk chunk(int x, int z) {
            int chunkX = Math.floorDiv(x, 16), chunkZ = Math.floorDiv(z, 16);
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
            LevelChunk cached = loadedChunks.get(key);
            if (cached != null) return cached;
            if (unavailableChunks.contains(key)) return null;
            // ClientLevel.hasChunk() is deliberately unconditional in this
            // Minecraft version. Asking the client cache with create=false is
            // the non-loading presence check; it returns null outside storage.
            LevelChunk found = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (found == null) unavailableChunks.add(key);
            else loadedChunks.put(key, found);
            return found;
        }

        boolean loaded(int x, int z) { return chunk(x, z) != null; }

        void recordSlice(long elapsed) {
            scanSlices++; clientThreadNanos += elapsed; maxSliceNanos = Math.max(maxSliceNanos, elapsed);
        }

        JsonObject assemble() {
            if (loadedUnits == 0) {
                throw new BridgeException(BridgeError.NO_LOADED_COVERAGE, "spatial map has no loaded client-chunk coverage",
                        coverageErrorData());
            }
            if (request.requireComplete() && unavailableUnits > 0) {
                throw new BridgeException(BridgeError.OUTSIDE_LOADED_AREA, "spatial map includes unavailable client chunks",
                        coverageErrorData());
            }
            JsonObject result = encode();
            result.addProperty("response_bytes", 0);
            int responseBytes = -1;
            for (int attempt = 0; attempt < 4; attempt++) {
                int measured = result.toString().getBytes(StandardCharsets.UTF_8).length;
                if (measured == responseBytes) break;
                responseBytes = measured; result.addProperty("response_bytes", responseBytes);
            }
            if (responseBytes > request.limits().maxResponseBytes()) {
                JsonObject data = new JsonObject(); data.addProperty("reason", "query_too_large");
                data.addProperty("response_bytes", responseBytes); data.addProperty("max_response_bytes", request.limits().maxResponseBytes());
                data.addProperty("suggestion", "use a larger cell_size, a smaller region, or a smaller material_mix_limit");
                throw new BridgeException(BridgeError.QUERY_TOO_LARGE, "spatial map exceeds the response-size limit", data);
            }
            return result;
        }

        JsonObject common(List<String> palette) {
            JsonObject result = new JsonObject();
            result.addProperty("map_id", UUID.randomUUID().toString()); result.addProperty("representation", request.representation());
            result.addProperty("dimension", dimension); result.addProperty("runtime_mode", runtimeMode);
            if (replayTimeUs != null) result.addProperty("replay_time_us", replayTimeUs);
            result.add("requested_bounds", request.requested().json()); result.add("effective_bounds", request.effective().json());
            result.addProperty("cell_size", request.cellSize()); result.addProperty("material_mix_limit", request.materialMixLimit());
            if (request.fallback()) {
                result.addProperty("refines_map_id", request.refinesMapId()); result.addProperty("fallback_reason", request.fallbackReason());
            }
            JsonObject grid = new JsonObject(); JsonObject origin = new JsonObject();
            origin.addProperty("x", request.effective().minX()); origin.addProperty("y", request.effective().minY()); origin.addProperty("z", request.effective().minZ());
            grid.add("origin", origin); grid.addProperty("cell_size", request.cellSize());
            JsonObject dimensions = new JsonObject(); dimensions.addProperty("x", request.gridX()); dimensions.addProperty("y", request.gridY()); dimensions.addProperty("z", request.gridZ());
            grid.add("dimensions", dimensions); grid.addProperty("column_order", "increasing_z_then_x");
            grid.addProperty("vertical_order", "increasing_y");
            grid.addProperty("coordinate_formula", request.representation().equals("surface")
                    ? "x/z_bounds=[origin_axis+axis_index*cell_size,origin_axis+(axis_index+1)*cell_size); Y uses sampled_y_bounds"
                    : "axis_bounds=[origin_axis+axis_index*cell_size,origin_axis+(axis_index+1)*cell_size)");
            result.add("grid", grid);
            JsonArray paletteJson = new JsonArray(); palette.forEach(paletteJson::add); result.add("palette", paletteJson);
            result.addProperty("capture_start_tick", captureStartTick); result.addProperty("capture_end_tick", captureEndTick);
            result.addProperty("consistency", captureStartTick == captureEndTick ? "single_tick" : "multi_tick");
            result.addProperty("atomic", captureStartTick == captureEndTick);
            JsonObject coverage = new JsonObject(); coverage.addProperty("state", unavailableUnits == 0 ? "complete" : "partial");
            coverage.addProperty("loaded_source_units", loadedUnits); coverage.addProperty("unavailable_source_units", unavailableUnits);
            result.add("coverage", coverage);
            JsonObject work = new JsonObject(); work.addProperty("aggregate_cells", request.aggregateCells());
            work.addProperty(request.representation().equals("surface") ? "source_columns" : "logical_voxels", request.sourceWork());
            work.addProperty("scan_slices", scanSlices); work.addProperty("client_thread_us", TimeUnit.NANOSECONDS.toMicros(clientThreadNanos));
            work.addProperty("max_slice_us", TimeUnit.NANOSECONDS.toMicros(maxSliceNanos)); work.addProperty("hard_slice_overruns", hardSliceOverruns);
            result.add("work", work); return result;
        }

        JsonObject coverageErrorData() {
            JsonObject data = new JsonObject(); data.add("requested_bounds", request.requested().json()); data.add("effective_bounds", request.effective().json());
            data.addProperty("loaded_source_units", loadedUnits); data.addProperty("unavailable_source_units", unavailableUnits);
            data.add("unavailable_boxes", unavailableBoxes()); return data;
        }

        abstract JsonArray unavailableBoxes();

        JsonArray unavailableChunkBoxes(int minY, int maxY, boolean volume) {
            JsonArray boxes = new JsonArray();
            int minChunkX = Math.floorDiv(request.effective().minX(), 16), maxChunkX = Math.floorDiv(request.effective().maxX() - 1, 16);
            int minChunkZ = Math.floorDiv(request.effective().minZ(), 16), maxChunkZ = Math.floorDiv(request.effective().maxZ() - 1, 16);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int runStart = Integer.MIN_VALUE;
                for (int chunkX = minChunkX; chunkX <= maxChunkX + 1; chunkX++) {
                    boolean unavailable = chunkX <= maxChunkX
                            && unavailableChunks.contains(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
                    if (unavailable && runStart == Integer.MIN_VALUE) runStart = chunkX;
                    if (unavailable || runStart == Integer.MIN_VALUE) continue;
                    int minX = Math.max(request.effective().minX(), runStart * 16);
                    int maxX = Math.min(request.effective().maxX(), chunkX * 16);
                    int minZ = Math.max(request.effective().minZ(), chunkZ * 16);
                    int maxZ = Math.min(request.effective().maxZ(), (chunkZ + 1) * 16);
                    long units = (long) (maxX - minX) * (maxZ - minZ) * (volume ? maxY - minY : 1L);
                    boxes.add(box(minX, maxX, minY, maxY, minZ, maxZ, 0, units)); runStart = Integer.MIN_VALUE;
                }
            }
            return boxes;
        }
    }

    private final class SurfaceTask extends Task {
        final SurfaceCell[] cells;
        final Heightmap.Types heightmapType;
        final Predicate<BlockState> predicate;
        int cellIndex;
        int localX;
        int localZ;
        int scanY;
        boolean scanningColumn;
        int loadedColumns;
        int unavailableColumns;
        int surfaceColumns;
        int minSurfaceY;
        int maxSurfaceY;
        long surfaceYSum;
        final Map<String, Integer> materials = new HashMap<>();

        SurfaceTask(ClientLevel level, SpatialMapContract.Request request, CancellationToken cancellation,
                    String runtimeMode, Long replayTimeUs, long deadlineMs, Runnable stateGuard) {
            super(level, request, cancellation, runtimeMode, replayTimeUs, deadlineMs, stateGuard);
            cells = new SurfaceCell[Math.toIntExact(request.aggregateCells())];
            heightmapType = request.surfaceMode().equals("world_surface") ? Heightmap.Types.WORLD_SURFACE : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            predicate = heightmapType.isOpaque();
        }

        @Override void step() {
            if (complete()) return;
            checkState();
            int cellX = cellIndex % request.gridX(), cellZ = cellIndex / request.gridX();
            int x = request.effective().minX() + cellX * request.cellSize() + localX;
            int z = request.effective().minZ() + cellZ * request.cellSize() + localZ;
            LevelChunk chunk = chunk(x, z);
            if (!scanningColumn) {
                if (chunk == null) { unavailableColumns++; unavailableUnits++; advanceColumn(); return; }
                int nativeSurfaceY = chunk.getHeight(heightmapType, x, z) + 1;
                if (nativeSurfaceY <= request.requested().minY()) { loadedColumns++; loadedUnits++; advanceColumn(); return; }
                if (nativeSurfaceY <= request.requested().maxY()) {
                    acceptSurface(chunk.getBlockState(new BlockPos(x, nativeSurfaceY - 1, z)), nativeSurfaceY);
                    return;
                }
                scanningColumn = true; scanY = request.requested().maxY() - 1;
            }
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            while (scanY >= request.requested().minY()) {
                BlockState state = chunk.getBlockState(position.set(x, scanY, z));
                if (predicate.test(state)) {
                    acceptSurface(state, scanY + 1); return;
                }
                scanY--;
                if ((scanY & 31) == 0) return;
            }
            loadedColumns++; loadedUnits++; scanningColumn = false; advanceColumn();
        }

        private void acceptSurface(BlockState state, int surfaceY) {
            if (surfaceColumns == 0) { minSurfaceY = surfaceY; maxSurfaceY = surfaceY; }
            else { minSurfaceY = Math.min(minSurfaceY, surfaceY); maxSurfaceY = Math.max(maxSurfaceY, surfaceY); }
            surfaceColumns++; surfaceYSum += surfaceY;
            materials.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
            loadedColumns++; loadedUnits++; scanningColumn = false; advanceColumn();
        }

        private void advanceColumn() {
            localX++;
            if (localX < request.cellSize()) return;
            localX = 0; localZ++;
            if (localZ < request.cellSize()) return;
            cells[cellIndex] = new SurfaceCell(loadedColumns, unavailableColumns, surfaceColumns, minSurfaceY,
                    surfaceColumns == 0 ? 0 : (double) surfaceYSum / surfaceColumns, maxSurfaceY, Map.copyOf(materials));
            cellIndex++; localZ = 0; loadedColumns = 0; unavailableColumns = 0; surfaceColumns = 0;
            minSurfaceY = 0; maxSurfaceY = 0; surfaceYSum = 0; materials.clear();
        }

        @Override boolean complete() { return cellIndex >= cells.length; }

        @Override JsonObject encode() {
            List<String> palette = palette(cells);
            Map<String, Integer> indices = indices(palette);
            JsonObject result = common(palette); result.addProperty("surface_mode", request.surfaceMode());
            JsonObject sampledY = new JsonObject(); sampledY.addProperty("min_y", request.requested().minY()); sampledY.addProperty("max_y", request.requested().maxY());
            result.add("sampled_y_bounds", sampledY);
            JsonObject fields = new JsonObject();
            fields.addProperty("surface", "[state,min_surface_y,mean_surface_y,max_surface_y,dominant_palette_index,dominant_share,loaded_columns,total_columns,(optional surface_mix)]");
            fields.addProperty("empty", "[state,loaded_columns,total_columns]"); result.add("cell_fields", fields);
            JsonArray rows = new JsonArray();
            for (int z = 0; z < request.gridZ(); z++) {
                JsonObject row = new JsonObject(); int minZ = request.effective().minZ() + z * request.cellSize();
                row.addProperty("min_z", minZ); row.addProperty("max_z", minZ + request.cellSize());
                JsonArray encoded = new JsonArray();
                for (int x = 0; x < request.gridX(); x++) encoded.add(surfaceCell(cells[z * request.gridX() + x], indices));
                row.add("cells", encoded); rows.add(row);
            }
            result.add("rows", rows); result.add("unavailable_boxes", unavailableBoxes()); return result;
        }

        private JsonArray surfaceCell(SurfaceCell cell, Map<String, Integer> indices) {
            JsonArray value = new JsonArray(); int total = request.cellSize() * request.cellSize();
            if (cell.loaded == 0) { value.add("unavailable"); value.add(0); value.add(total); return value; }
            String prefix = cell.unavailable == 0 ? "" : "partial_";
            if (cell.surface == 0) { value.add(prefix + "empty"); value.add(cell.loaded); value.add(total); return value; }
            List<Map.Entry<String, Integer>> mix = sortedMix(cell.materials); Map.Entry<String, Integer> dominant = mix.getFirst();
            value.add(prefix + "surface"); value.add(cell.minY); value.add(round(cell.meanY, 1)); value.add(cell.maxY);
            value.add(indices.get(dominant.getKey())); value.add(round((double) dominant.getValue() / cell.surface, 4));
            value.add(cell.loaded); value.add(total);
            if (request.materialMixLimit() > 1) value.add(mix(mix, indices, cell.surface, request.materialMixLimit()));
            return value;
        }

        @Override JsonArray unavailableBoxes() {
            return unavailableChunkBoxes(request.requested().minY(), request.requested().maxY(), false);
        }
    }

    private final class VolumeTask extends Task {
        final VolumeCell[] cells;
        final Predicate<BlockState> solid = state -> state.blocksMotion() && !state.is(BlockTags.LEAVES);
        int cellIndex;
        int localX;
        int localY;
        int localZ;
        int loadedVoxels;
        int unavailableVoxels;
        int solidVoxels;
        int fluidVoxels;
        final Map<String, Integer> exposedMaterials = new HashMap<>();

        VolumeTask(ClientLevel level, SpatialMapContract.Request request, CancellationToken cancellation,
                   String runtimeMode, Long replayTimeUs, long deadlineMs, Runnable stateGuard) {
            super(level, request, cancellation, runtimeMode, replayTimeUs, deadlineMs, stateGuard);
            cells = new VolumeCell[Math.toIntExact(request.aggregateCells())];
        }

        @Override void step() {
            if (complete()) return;
            checkState();
            int perColumn = request.gridY(); int column = cellIndex / perColumn; int yIndex = cellIndex % perColumn;
            int xIndex = column % request.gridX(), zIndex = column / request.gridX();
            if (localX == 0 && localY == 0 && localZ == 0 && wholeCellIsLoadedAir(xIndex, yIndex, zIndex)) {
                int total = request.cellSize() * request.cellSize() * request.cellSize();
                loadedUnits += total; cells[cellIndex] = new VolumeCell(total, 0, 0, 0, Map.of()); cellIndex++; return;
            }
            int x = request.effective().minX() + xIndex * request.cellSize() + localX;
            int y = request.effective().minY() + yIndex * request.cellSize() + localY;
            int z = request.effective().minZ() + zIndex * request.cellSize() + localZ;
            LevelChunk sourceChunk = chunk(x, z);
            if (sourceChunk == null) { unavailableVoxels++; unavailableUnits++; advanceVoxel(); return; }
            BlockPos position = new BlockPos(x, y, z); BlockState state = sourceChunk.getBlockState(position);
            loadedVoxels++; loadedUnits++;
            if (!state.getFluidState().isEmpty()) fluidVoxels++;
            if (solid.test(state)) {
                solidVoxels++; int exposedFaces = 0;
                for (Direction direction : Direction.values()) {
                    BlockPos neighbor = position.relative(direction);
                    if (neighbor.getY() < level.getMinY() || neighbor.getY() > level.getMaxY()) { exposedFaces++; continue; }
                    LevelChunk neighborChunk = chunk(neighbor.getX(), neighbor.getZ());
                    if (neighborChunk == null) continue;
                    if (!solid.test(neighborChunk.getBlockState(neighbor))) exposedFaces++;
                }
                if (exposedFaces > 0) exposedMaterials.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), exposedFaces, Integer::sum);
            }
            advanceVoxel();
        }

        private boolean wholeCellIsLoadedAir(int xIndex, int yIndex, int zIndex) {
            int minX = request.effective().minX() + xIndex * request.cellSize(), maxX = minX + request.cellSize();
            int minY = request.effective().minY() + yIndex * request.cellSize(), maxY = minY + request.cellSize();
            int minZ = request.effective().minZ() + zIndex * request.cellSize(), maxZ = minZ + request.cellSize();
            for (int chunkZ = Math.floorDiv(minZ, 16); chunkZ <= Math.floorDiv(maxZ - 1, 16); chunkZ++) {
                for (int chunkX = Math.floorDiv(minX, 16); chunkX <= Math.floorDiv(maxX - 1, 16); chunkX++) {
                    var chunk = chunk(chunkX * 16, chunkZ * 16);
                    if (chunk == null) return false;
                    for (int y = minY; y < maxY; y = Math.min(maxY, (Math.floorDiv(y, 16) + 1) * 16)) {
                        if (!chunk.getSection(level.getSectionIndex(y)).hasOnlyAir()) return false;
                    }
                }
            }
            return true;
        }

        private void advanceVoxel() {
            localY++;
            if (localY < request.cellSize()) return;
            localY = 0; localX++;
            if (localX < request.cellSize()) return;
            localX = 0; localZ++;
            if (localZ < request.cellSize()) return;
            cells[cellIndex] = new VolumeCell(loadedVoxels, unavailableVoxels, solidVoxels, fluidVoxels, Map.copyOf(exposedMaterials));
            cellIndex++; localZ = 0; loadedVoxels = 0; unavailableVoxels = 0; solidVoxels = 0; fluidVoxels = 0; exposedMaterials.clear();
        }

        @Override boolean complete() { return cellIndex >= cells.length; }

        @Override JsonObject encode() {
            List<String> palette = palette(cells); Map<String, Integer> indices = indices(palette);
            JsonObject result = common(palette);
            result.addProperty("occupancy_predicate", "motion_blocking_no_leaves_solid_with_fluids_separate");
            result.addProperty("empty_intervals", "implicit only when column.fully_loaded is true");
            result.addProperty("run_fields", "[min_y,max_y,state,solid_fraction,fluid_fraction,dominant_exposed_palette_index,dominant_share,loaded_voxels,total_voxels,(optional exposed_mix)]");
            JsonArray columns = new JsonArray(); int total = request.cellSize() * request.cellSize() * request.cellSize();
            for (int z = 0; z < request.gridZ(); z++) for (int x = 0; x < request.gridX(); x++) {
                JsonObject column = new JsonObject(); int minX = request.effective().minX() + x * request.cellSize(); int minZ = request.effective().minZ() + z * request.cellSize();
                JsonArray xBounds = new JsonArray(); xBounds.add(minX); xBounds.add(minX + request.cellSize()); column.add("x", xBounds);
                JsonArray zBounds = new JsonArray(); zBounds.add(minZ); zBounds.add(minZ + request.cellSize()); column.add("z", zBounds);
                JsonArray runs = new JsonArray(); JsonArray previous = null; boolean fullyLoaded = true;
                for (int y = 0; y < request.gridY(); y++) {
                    VolumeCell cell = cells[((z * request.gridX() + x) * request.gridY()) + y];
                    if (cell.unavailable > 0) fullyLoaded = false;
                    JsonArray run = volumeCell(cell, indices, request.effective().minY() + y * request.cellSize(), total);
                    if (run == null) continue;
                    if (previous != null && sameRun(previous, run)) previous.set(1, run.get(1));
                    else { runs.add(run); previous = run; }
                }
                column.addProperty("fully_loaded", fullyLoaded); column.add("runs", runs); columns.add(column);
            }
            result.add("columns", columns); result.add("unavailable_boxes", unavailableBoxes()); return result;
        }

        private JsonArray volumeCell(VolumeCell cell, Map<String, Integer> indices, int minY, int total) {
            if (cell.loaded == 0) return null;
            boolean partial = cell.unavailable > 0; String state;
            if (cell.solid == cell.loaded) state = "full_solid";
            else if (cell.solid == 0 && cell.fluid == cell.loaded) state = "full_fluid";
            else if (cell.solid == 0 && cell.fluid == 0) state = "empty";
            else state = "mixed";
            if (!partial && state.equals("empty")) return null;
            if (partial) state = "partial_" + state;
            JsonArray run = new JsonArray(); run.add(minY); run.add(minY + request.cellSize()); run.add(state);
            run.add(round((double) cell.solid / cell.loaded, 4)); run.add(round((double) cell.fluid / cell.loaded, 4));
            List<Map.Entry<String, Integer>> mix = sortedMix(cell.materials);
            if (mix.isEmpty()) { run.add(JsonNull.INSTANCE); run.add(JsonNull.INSTANCE); }
            else {
                int faces = mix.stream().mapToInt(Map.Entry::getValue).sum(); Map.Entry<String, Integer> dominant = mix.getFirst();
                run.add(indices.get(dominant.getKey())); run.add(round((double) dominant.getValue() / faces, 4));
            }
            run.add(cell.loaded); run.add(total);
            if (request.materialMixLimit() > 1) {
                int faces = mix.stream().mapToInt(Map.Entry::getValue).sum(); run.add(mix(mix, indices, faces, request.materialMixLimit()));
            }
            return run;
        }

        @Override JsonArray unavailableBoxes() {
            return unavailableChunkBoxes(request.effective().minY(), request.effective().maxY(), true);
        }
    }

    private interface MaterialCell { Map<String, Integer> materials(); }
    private record SurfaceCell(int loaded, int unavailable, int surface, int minY, double meanY, int maxY,
                               Map<String, Integer> materials) implements MaterialCell {}
    private record VolumeCell(int loaded, int unavailable, int solid, int fluid,
                              Map<String, Integer> materials) implements MaterialCell {}

    private static List<String> palette(MaterialCell[] cells) {
        return java.util.Arrays.stream(cells).filter(java.util.Objects::nonNull).flatMap(cell -> cell.materials().keySet().stream()).distinct().sorted().toList();
    }
    private static Map<String, Integer> indices(List<String> palette) {
        Map<String, Integer> result = new HashMap<>(); for (int index = 0; index < palette.size(); index++) result.put(palette.get(index), index); return result;
    }
    private static List<Map.Entry<String, Integer>> sortedMix(Map<String, Integer> materials) {
        List<Map.Entry<String, Integer>> result = new ArrayList<>(materials.entrySet());
        result.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey())); return result;
    }
    private static JsonArray mix(List<Map.Entry<String, Integer>> entries, Map<String, Integer> indices, int denominator, int limit) {
        JsonArray result = new JsonArray();
        entries.stream().limit(limit).forEach(entry -> {
            JsonArray item = new JsonArray(); item.add(indices.get(entry.getKey())); item.add(round((double) entry.getValue() / denominator, 4)); result.add(item);
        });
        return result;
    }
    private static JsonObject box(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, long loaded, long unavailable) {
        JsonObject value = new JsonObject(); JsonObject bounds = new JsonObject();
        bounds.addProperty("min_x", minX); bounds.addProperty("max_x", maxX); bounds.addProperty("min_y", minY); bounds.addProperty("max_y", maxY);
        bounds.addProperty("min_z", minZ); bounds.addProperty("max_z", maxZ); value.add("bounds", bounds);
        value.addProperty("loaded_source_units", loaded); value.addProperty("unavailable_source_units", unavailable);
        return value;
    }
    private static double round(double value, int places) { double scale = Math.pow(10, places); return Math.round(value * scale) / scale; }
    private static boolean sameRun(JsonArray left, JsonArray right) {
        if (left.size() != right.size()) return false;
        for (int index = 2; index < left.size(); index++) if (!left.get(index).equals(right.get(index))) return false;
        return left.get(1).getAsInt() == right.get(0).getAsInt();
    }
}
