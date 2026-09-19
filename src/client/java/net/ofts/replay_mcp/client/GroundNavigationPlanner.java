package net.ofts.replay_mcp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.util.Set;

/** Plans conservative ground routes with Minecraft's native walker. */
final class GroundNavigationPlanner {
    static final double MAX_DISTANCE = 128.0;
    static final int MAX_VISITED_NODES = 8_192;
    static final int MAX_REPLANS = 3;
    static final int STUCK_TICKS = 40;
    static final int ARRIVAL_SETTLE_TICKS = 4;
    private static final int REGION_PADDING = 16;

    record Plan(Path path, Vec3 target, double tolerance) { }

    private final Minecraft minecraft;

    GroundNavigationPlanner(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    Plan plan(Vec3 target, double tolerance) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) throw conflict("no_path", "ground navigation requires a live player");
        if (minecraft.player.isPassenger()) throw conflict("unsupported_terrain", "ground navigation is unavailable while riding");
        if (minecraft.player.isInWater() || minecraft.player.isInLava()) {
            throw conflict("unsupported_terrain", "ground navigation does not support swimming or lava");
        }
        Vec3 start = minecraft.player.position();
        if (start.distanceTo(target) > MAX_DISTANCE) {
            throw conflict("target_out_of_range", "navigation target is more than 128 blocks away");
        }
        BlockPos startPos = BlockPos.containing(start);
        BlockPos targetPos = BlockPos.containing(target);
        if (level.isOutsideBuildHeight(targetPos)) throw conflict("target_out_of_range", "navigation target is outside build height");

        BlockPos min = new BlockPos(Math.min(startPos.getX(), targetPos.getX()) - REGION_PADDING,
                Math.max(level.getMinY(), Math.min(startPos.getY(), targetPos.getY()) - REGION_PADDING),
                Math.min(startPos.getZ(), targetPos.getZ()) - REGION_PADDING);
        BlockPos max = new BlockPos(Math.max(startPos.getX(), targetPos.getX()) + REGION_PADDING,
                Math.min(level.getMaxY() - 1, Math.max(startPos.getY(), targetPos.getY()) + REGION_PADDING),
                Math.max(startPos.getZ(), targetPos.getZ()) + REGION_PADDING);
        requireLoaded(level, min, max);

        // EntityType#create deliberately returns null for client worlds. The path finder only
        // needs a detached mob for dimensions and malus values, so construct the vanilla
        // zombie directly without adding it to the level.
        Mob proxy = new Zombie(level);
        proxy.setPos(start);
        configureGroundSafety(proxy);

        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        // Match vanilla walking semantics: an already-open doorway is ordinary walkable
        // space, while closed doors remain blocked because this controller must not alter
        // the scene by opening them.
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(false);
        evaluator.setCanFloat(false);
        evaluator.setCanWalkOverFences(false);
        PathFinder finder = new PathFinder(evaluator, MAX_VISITED_NODES);
        Path path = finder.findPath(new PathNavigationRegion(level, min, max), proxy, Set.of(targetPos),
                (float) MAX_DISTANCE, 0, 1.0f);
        if (path == null || path.getNodeCount() == 0 || path.getEndNode() == null
                || path.getEndNode().distanceTo(targetPos) > 0.0f) {
            throw conflict("no_path", "Minecraft could not find a complete ground path to the target");
        }
        return new Plan(path, target, tolerance);
    }

    private static void configureGroundSafety(Mob proxy) {
        for (PathType type : new PathType[]{PathType.WATER, PathType.WATER_BORDER, PathType.BREACH, PathType.LAVA,
                PathType.FIRE, PathType.FIRE_IN_NEIGHBOR, PathType.DAMAGING, PathType.DAMAGING_IN_NEIGHBOR,
                PathType.DAMAGE_CAUTIOUS, PathType.POWDER_SNOW, PathType.ON_TOP_OF_POWDER_SNOW,
                PathType.FENCE, PathType.TRAPDOOR,
                PathType.DOOR_WOOD_CLOSED, PathType.DOOR_IRON_CLOSED}) {
            proxy.setPathfindingMalus(type, -1.0f);
        }
    }

    private static void requireLoaded(ClientLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4, maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4, maxChunkZ = max.getZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    throw conflict("outside_loaded_area", "navigation planning never loads missing chunks");
                }
            }
        }
    }

    static BridgeException conflict(String reason, String message) {
        JsonObject data = new JsonObject(); data.addProperty("reason", reason);
        return new BridgeException(BridgeError.CONFLICT, message, data);
    }
}
