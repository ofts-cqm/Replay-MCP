package net.ofts.replay_mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.versions.MCVer;
import com.replaymod.recording.ReplayModRecording;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import com.replaymod.simplepathing.ReplayModSimplePathing;
import com.replaymod.simplepathing.SPTimeline;
import com.replaymod.simplepathing.InterpolatorType;
import com.replaymod.render.RenderSettings;
import com.replaymod.render.ReplayModRender;
import com.replaymod.render.rendering.VideoRenderer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.ofts.replay_mcp.bridge.BridgeAdapter;
import net.ofts.replay_mcp.bridge.EventSink;
import net.ofts.replay_mcp.artifact.ArtifactStore;
import net.ofts.replay_mcp.observation.StableObservationIds;
import net.ofts.replay_mcp.operation.CancellationToken;
import net.ofts.replay_mcp.mixin.client.PacketListenerAccessor;
import net.ofts.replay_mcp.mixin.client.ScreenInvoker;
import net.ofts.replay_mcp.mixin.client.ChatComponentAccessor;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

final class MinecraftBridgeAdapter implements BridgeAdapter, AutoCloseable {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final ArtifactStore artifacts;
    private final net.ofts.replay_mcp.config.ReplayMcpConfig config;
    private final Set<KeyMapping> held = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final StableObservationIds<Entity> entityIds = new StableObservationIds<>(new java.security.SecureRandom());
    private final StableObservationIds<GuiEventListener> widgetIds = new StableObservationIds<>(new java.security.SecureRandom());
    private Object observedLevel;
    private Object observedScreen;
    private boolean logicalRecording;
    private String takeId;
    private Path pendingRecordingPath;
    private long pendingRecordingSize = -1;
    private int pendingRecordingStableTicks;
    private ReplayFile openReplay;
    private Path replaySource;
    private Path replayWorkingCopy;
    private boolean replayDirty;
    private PendingBatch pendingBatch;
    private final ScheduledExecutorService renderMonitor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "replay-mcp-render-monitor"); t.setDaemon(true); return t; });
    private volatile VideoRenderer activeRenderer;
    private volatile String activeRenderJob;
    private volatile Path activeRenderOutput;
    private volatile Path activeRenderPartial;
    private volatile boolean activeRenderCancelled;
    private volatile EventSink events = EventSink.NONE;

    MinecraftBridgeAdapter(ArtifactStore artifacts, net.ofts.replay_mcp.config.ReplayMcpConfig config) {
        this.artifacts = artifacts; this.config = config;
    }

    @Override public void eventSink(EventSink sink) { events = sink == null ? EventSink.NONE : sink; }

    @Override public JsonObject status() {
        return onClient(() -> {
            JsonObject result = new JsonObject();
            result.addProperty("minecraft_version", minecraft.getLaunchedVersion());
            result.addProperty("replay_mod_version", ReplayMod.instance == null ? "unavailable" : ReplayMod.instance.getVersion());
            result.addProperty("connected", minecraft.level != null);
            result.addProperty("runtime_mode", replayHandler() != null ? "replay_loaded" : minecraft.level != null ? "live_idle" : "game_offline");
            result.addProperty("replay_ready", replayHandler() != null && replayHandler().getCameraEntity() != null);
            result.addProperty("bridge_enabled", config.bridgeEnabled);
            return result;
        });
    }

    @Override public JsonObject capabilities() {
        JsonObject result = new JsonObject();
        JsonArray families = new JsonArray();
        for (String family : new String[]{"system", "lease", "observation", "action", "recording", "replay", "timeline", "render"}) families.add(family);
        result.add("families", families);
        result.addProperty("normal_input", true); result.addProperty("structured_observation", true);
        result.addProperty("framebuffer_capture", true); result.addProperty("clean_capture", true); result.addProperty("annotated_capture", true); result.addProperty("native_timeline", true);
        result.addProperty("native_fov", false); result.addProperty("native_look_at", false); result.addProperty("sidecar_editorial_tracks", false);
        result.addProperty("time_precision_us", 1_000);
        return result;
    }

    @Override public boolean flightGranted() {
        return onClient(() -> minecraft.player != null && minecraft.player.getAbilities().mayfly);
    }

    @Override public void releaseAllInputs() {
        minecraft.execute(() -> { held.forEach(key -> key.setDown(false)); held.clear(); });
    }

    @Override public void timelineChanged(JsonObject state) {
        onClient(() -> {
            if (openReplay == null || ReplayModSimplePathing.instance == null || ReplayModSimplePathing.instance.getCurrentTimeline() == null) {
                throw new BridgeException(BridgeError.INVALID_MODE, "no editable Replay Mod timeline is loaded");
            }
            SPTimeline timeline = ReplayModSimplePathing.instance.getCurrentTimeline();
            timeline.getTimePath().getKeyframes().stream().toList().forEach(k -> timeline.getTimePath().remove(k, true));
            timeline.getPositionPath().getKeyframes().stream().toList().forEach(k -> timeline.getPositionPath().remove(k, true));
            JsonObject tracks = state.getAsJsonObject("tracks");
            if (tracks.has("replay_time")) for (JsonElement element : tracks.getAsJsonArray("replay_time")) {
                JsonObject frame = element.getAsJsonObject(); long outputMs = frame.get("time_us").getAsLong() / 1_000L;
                long replayUs = frame.has("replay_time_us") ? frame.get("replay_time_us").getAsLong() : frame.get("value").getAsLong();
                timeline.addTimeKeyframe(outputMs, Math.toIntExact(replayUs / 1_000L));
            }
            java.util.TreeSet<Long> positionTimes = new java.util.TreeSet<>();
            for (String name : new String[]{"camera_position", "yaw", "pitch", "roll", "spectated_entity"}) if (tracks.has(name))
                for (JsonElement element : tracks.getAsJsonArray(name)) positionTimes.add(element.getAsJsonObject().get("time_us").getAsLong());
            for (long timeUs : positionTimes) {
                JsonObject position = at(tracks, "camera_position", timeUs); if (position == null) continue;
                double x = number(position, "x", 0), y = number(position, "y", 0), z = number(position, "z", 0);
                float yaw = (float) number(at(tracks, "yaw", timeUs), "value", 0), pitch = (float) number(at(tracks, "pitch", timeUs), "value", 0), roll = (float) number(at(tracks, "roll", timeUs), "value", 0);
                int spectated = (int) number(at(tracks, "spectated_entity", timeUs), "value", -1);
                timeline.addPositionKeyframe(timeUs / 1_000L, x, y, z, yaw, pitch, roll, spectated);
            }
            replayDirty = true;
            return null;
        });
    }

    @Override public JsonElement invoke(String method, JsonObject params, CancellationToken cancellation) {
        return switch (method) {
            case "observation.snapshot", "observation.query" -> snapshot();
            case "observation.framebuffer" -> captureFrame(params);
            case "observation.motion_burst" -> motionBurst(params, cancellation);
            case "action.start_batch" -> perform(params, cancellation);
            case "recording.status" -> recordingStatus();
            case "recording.start" -> recordingStart(params);
            case "recording.stop" -> recordingStop();
            case "recording.marker" -> marker(params);
            case "replay.list" -> replayList();
            case "replay.metadata" -> replayMetadata(params);
            case "replay.open" -> replayOpen(params);
            case "replay.close" -> replayClose();
            case "replay.save" -> replaySave(params);
            case "replay.playback" -> playback(params);
            case "render.start" -> renderStart(params);
            case "render.cancel" -> renderCancel(params);
            case "render.still" -> renderStill(params);
            default -> BridgeAdapter.super.invoke(method, params, cancellation);
        };
    }

    private JsonObject snapshot() {
        return onClient(() -> {
            JsonObject result = new JsonObject();
            result.addProperty("synchronized", true);
            result.addProperty("tick", minecraft.level == null ? 0 : minecraft.level.getGameTime());
            if (minecraft.player != null) {
                if (observedLevel != minecraft.level) { observedLevel = minecraft.level; entityIds.resetSession(); }
                JsonObject player = new JsonObject(); Vec3 position = minecraft.player.position();
                player.addProperty("x", position.x); player.addProperty("y", position.y); player.addProperty("z", position.z);
                player.addProperty("yaw", minecraft.player.getYRot()); player.addProperty("pitch", minecraft.player.getXRot());
                player.addProperty("health", minecraft.player.getHealth()); player.addProperty("food", minecraft.player.getFoodData().getFoodLevel());
                player.addProperty("flying", minecraft.player.getAbilities().flying); player.addProperty("flight_granted", minecraft.player.getAbilities().mayfly);
                player.addProperty("selected_hotbar_slot", minecraft.player.getInventory().getSelectedSlot());
                player.addProperty("selected_item", minecraft.player.getMainHandItem().isEmpty() ? "minecraft:air" : minecraft.player.getMainHandItem().getItem().toString());
                if (minecraft.gameMode != null) player.addProperty("game_mode", minecraft.gameMode.getPlayerMode().getName());
                JsonArray effects = new JsonArray();
                minecraft.player.getActiveEffects().forEach(effect -> {
                    JsonObject value = new JsonObject(); value.addProperty("effect", effect.getEffect().getRegisteredName()); value.addProperty("amplifier", effect.getAmplifier());
                    value.addProperty("duration_ticks", effect.getDuration()); value.addProperty("ambient", effect.isAmbient()); effects.add(value);
                });
                player.add("effects", effects);
                result.add("player", player);
                JsonArray inventory = new JsonArray();
                var items = minecraft.player.getInventory().getNonEquipmentItems();
                for (int slot = 0; slot < items.size(); slot++) if (!items.get(slot).isEmpty()) {
                    JsonObject item = new JsonObject(); item.addProperty("slot", slot); item.addProperty("item", items.get(slot).getItem().toString()); item.addProperty("count", items.get(slot).getCount()); inventory.add(item);
                }
                result.add("inventory", inventory);
                JsonArray entities = new JsonArray();
                for (Entity entity : minecraft.level.entitiesForRendering()) if (entity != minecraft.player && entity.distanceToSqr(minecraft.player) <= 1024) {
                    JsonObject value = new JsonObject(); value.addProperty("observation_id", entityIds.register(entity)); value.addProperty("runtime_id", entity.getId());
                    value.addProperty("uuid", entity.getUUID().toString()); value.addProperty("type", entity.getType().toString()); value.addProperty("name", entity.getName().getString());
                    value.addProperty("x", entity.getX()); value.addProperty("y", entity.getY()); value.addProperty("z", entity.getZ()); value.addProperty("distance", entity.distanceTo(minecraft.player)); entities.add(value);
                    if (entities.size() >= 256) break;
                }
                result.add("nearby_entities", entities);
                JsonArray blocks = new JsonArray(); BlockPos center = minecraft.player.blockPosition();
                for (int dx = -8; dx <= 8; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -8; dz <= 8; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz); if (!minecraft.level.hasChunkAt(pos)) continue; var state = minecraft.level.getBlockState(pos); if (state.isAir()) continue;
                    JsonObject block = new JsonObject(); block.addProperty("observation_id", "block:" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()); block.addProperty("x", pos.getX()); block.addProperty("y", pos.getY()); block.addProperty("z", pos.getZ()); block.addProperty("state", state.toString()); blocks.add(block);
                    if (blocks.size() >= 4096) break;
                }
                result.add("loaded_nearby_blocks", blocks);
                JsonObject world = new JsonObject(); world.addProperty("dimension", minecraft.level.dimension().identifier().toString());
                world.addProperty("game_time", minecraft.level.getGameTime());
                world.addProperty("raining", minecraft.level.isRaining()); world.addProperty("thundering", minecraft.level.isThundering());
                world.addProperty("sky_light", minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, center));
                world.addProperty("block_light", minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, center)); result.add("world", world);

                JsonArray scoreboard = new JsonArray();
                for (var objective : minecraft.level.getScoreboard().getObjectives()) {
                    JsonObject board = new JsonObject(); board.addProperty("name", objective.getName()); board.addProperty("display_name", objective.getDisplayName().getString());
                    JsonArray scores = new JsonArray();
                    for (var score : minecraft.level.getScoreboard().listPlayerScores(objective)) {
                        if (score.isHidden()) continue;
                        JsonObject entry = new JsonObject(); entry.addProperty("owner", score.owner()); entry.addProperty("display", score.ownerName().getString()); entry.addProperty("value", score.value()); scores.add(entry);
                        if (scores.size() >= 256) break;
                    }
                    board.add("scores", scores); scoreboard.add(board); if (scoreboard.size() >= 64) break;
                }
                result.add("scoreboard", scoreboard);
            }
            var screen = minecraft.gui.screen();
            result.addProperty("screen", screen == null ? "none" : screen.getClass().getName());
            if (observedScreen != screen) { observedScreen = screen; widgetIds.resetSession(); }
            JsonArray widgets = new JsonArray();
            if (screen != null) for (GuiEventListener child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible) {
                JsonObject value = new JsonObject(); value.addProperty("observation_id", widgetIds.register(child)); value.addProperty("type", widget.getClass().getName());
                value.addProperty("label", widget.getMessage().getString()); value.addProperty("x", widget.getX()); value.addProperty("y", widget.getY());
                value.addProperty("width", widget.getWidth()); value.addProperty("height", widget.getHeight()); value.addProperty("active", widget.active); widgets.add(value);
            }
            result.add("widgets", widgets);
            JsonArray messages = new JsonArray();
            var allMessages = ((ChatComponentAccessor) minecraft.gui.hud.getChat()).replayMcp$allMessages();
            for (int index = Math.max(0, allMessages.size() - 100); index < allMessages.size(); index++) {
                var message = allMessages.get(index); JsonObject value = new JsonObject(); value.addProperty("text", message.content().getString());
                value.addProperty("tick", message.addedTime()); value.addProperty("source", message.source().toString().toLowerCase(java.util.Locale.ROOT)); messages.add(value);
            }
            result.add("chat_or_system_messages", messages);
            if (minecraft.hitResult != null) {
                JsonObject target = new JsonObject(); target.addProperty("type", minecraft.hitResult.getType().name().toLowerCase(java.util.Locale.ROOT));
                target.addProperty("x", minecraft.hitResult.getLocation().x); target.addProperty("y", minecraft.hitResult.getLocation().y); target.addProperty("z", minecraft.hitResult.getLocation().z);
                if (minecraft.hitResult instanceof EntityHitResult entityHit) target.addProperty("observation_id", entityIds.register(entityHit.getEntity()));
                if (minecraft.hitResult instanceof BlockHitResult blockHit) {
                    BlockPos pos = blockHit.getBlockPos(); target.addProperty("observation_id", "block:" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()); target.addProperty("face", blockHit.getDirection().getSerializedName());
                }
                result.add("crosshair_target", target);
            }
            if (replayHandler() != null) result.addProperty("replay_time_us", replayHandler().getReplaySender().currentTimeStamp() * 1_000L);
            result.addProperty("time_precision_us", 1_000);
            return result;
        });
    }

    private JsonObject captureFrame(JsonObject params) {
        String view = params.has("view") ? params.get("view").getAsString() : "player";
        if (!Set.of("player", "clean", "annotated").contains(view)) throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown observation view");
        CompletableFuture<com.mojang.blaze3d.platform.NativeImage> image;
        try { image = FrameCaptureCoordinator.request(!view.equals("player")); }
        catch (IllegalStateException e) { throw new BridgeException(BridgeError.CONFLICT, e.getMessage()); }
        try {
            com.mojang.blaze3d.platform.NativeImage captured = image.get(10, TimeUnit.SECONDS);
            try (captured) {
                JsonArray annotations = view.equals("annotated") ? annotationBounds(captured.getWidth(), captured.getHeight()) : new JsonArray();
                if (view.equals("annotated")) drawAnnotations(captured, annotations);
                Path output = artifacts.allocate("observations/" + UUID.randomUUID() + ".png");
                Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                captured.writeToFile(temporary); moveAtomic(temporary, output);
                JsonObject result = artifacts.describe(output, "image/png", captured.getWidth(), captured.getHeight(), true);
                result.addProperty("view", view); result.add("annotations", annotations); result.add("snapshot", snapshot()); return result;
            }
        } catch (TimeoutException e) { throw new BridgeException(BridgeError.TIMEOUT, "frame capture timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BridgeException(BridgeError.CANCELLED, "frame capture interrupted"); }
        catch (java.util.concurrent.ExecutionException | IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "frame capture failed"); }
    }

    private JsonObject motionBurst(JsonObject params, CancellationToken cancellation) {
        int count = params.has("frames") ? params.get("frames").getAsInt() : 8;
        if (count < 1 || count > 64) throw new BridgeException(BridgeError.INVALID_REQUEST, "motion burst frames must be between 1 and 64");
        String view = params.has("view") ? params.get("view").getAsString() : "clean";
        if (!Set.of("player", "clean", "annotated").contains(view)) throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown observation view");
        JsonArray frames = new JsonArray(); int dropped = 0;
        for (int index = 0; index < count; index++) {
            cancellation.throwIfCancelled();
            try {
                var future = FrameCaptureCoordinator.request(!view.equals("player"));
                try (var captured = future.get(2, TimeUnit.SECONDS)) {
                    JsonArray annotations = view.equals("annotated") ? annotationBounds(captured.getWidth(), captured.getHeight()) : new JsonArray();
                    if (view.equals("annotated")) drawAnnotations(captured, annotations);
                    Path output = artifacts.allocate("observations/bursts/" + UUID.randomUUID() + "-" + index + ".png"); Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                    captured.writeToFile(temporary); moveAtomic(temporary, output); JsonObject frame = artifacts.describe(output, "image/png", captured.getWidth(), captured.getHeight(), true);
                    frame.addProperty("timestamp_us", System.currentTimeMillis() * 1_000L); frame.add("annotations", annotations); frames.add(frame);
                }
            } catch (TimeoutException e) { dropped++; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BridgeException(BridgeError.CANCELLED, "motion burst interrupted"); }
            catch (java.util.concurrent.ExecutionException | IOException | IllegalStateException e) { dropped++; }
        }
        JsonObject result = new JsonObject(); result.add("frames", frames); result.addProperty("requested_frames", count); result.addProperty("dropped_frames", dropped); result.addProperty("view", view); return result;
    }

    private JsonArray annotationBounds(int width, int height) {
        return onClient(() -> {
            JsonArray annotations = new JsonArray();
            if (minecraft.level != null && minecraft.player != null) for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity == minecraft.player || entity.distanceToSqr(minecraft.player) > 1024) continue;
                Vec3 projected = minecraft.gameRenderer.projectPointToScreen(entity.position().add(0, entity.getBbHeight() * 0.5, 0));
                if (projected.z < -1 || projected.z > 1 || Math.abs(projected.x) > 1.2 || Math.abs(projected.y) > 1.2) continue;
                int centerX = (int) ((projected.x + 1) * 0.5 * width), centerY = (int) ((1 - projected.y) * 0.5 * height);
                int boxHeight = Math.max(12, (int) (height * Math.min(.5, entity.getBbHeight() / Math.max(2, entity.distanceTo(minecraft.player)) * .5)));
                int boxWidth = Math.max(8, (int) (boxHeight * Math.max(.25, entity.getBbWidth() / Math.max(.1, entity.getBbHeight()))));
                JsonObject item = new JsonObject(); item.addProperty("observation_id", entityIds.register(entity)); item.addProperty("kind", "entity");
                item.addProperty("x", centerX - boxWidth / 2); item.addProperty("y", centerY - boxHeight / 2); item.addProperty("width", boxWidth); item.addProperty("height", boxHeight); annotations.add(item);
            }
            if (minecraft.gui.screen() != null) for (GuiEventListener child : minecraft.gui.screen().children()) if (child instanceof AbstractWidget widget && widget.visible) {
                double scaleX = (double) width / minecraft.getWindow().getGuiScaledWidth(), scaleY = (double) height / minecraft.getWindow().getGuiScaledHeight();
                JsonObject item = new JsonObject(); item.addProperty("observation_id", widgetIds.register(child)); item.addProperty("kind", "widget");
                item.addProperty("x", (int) (widget.getX() * scaleX)); item.addProperty("y", (int) (widget.getY() * scaleY)); item.addProperty("width", (int) (widget.getWidth() * scaleX)); item.addProperty("height", (int) (widget.getHeight() * scaleY)); annotations.add(item);
            }
            return annotations;
        });
    }

    private static void drawAnnotations(com.mojang.blaze3d.platform.NativeImage image, JsonArray annotations) {
        for (JsonElement element : annotations) {
            JsonObject box = element.getAsJsonObject(); int x = box.get("x").getAsInt(), y = box.get("y").getAsInt(), w = box.get("width").getAsInt(), h = box.get("height").getAsInt();
            int color = 0xFF000000 | (box.get("observation_id").getAsString().hashCode() & 0x00FFFFFF);
            for (int px = Math.max(0, x); px < Math.min(image.getWidth(), x + w); px++) { if (y >= 0 && y < image.getHeight()) image.setPixelABGR(px, y, color); if (y + h - 1 >= 0 && y + h - 1 < image.getHeight()) image.setPixelABGR(px, y + h - 1, color); }
            for (int py = Math.max(0, y); py < Math.min(image.getHeight(), y + h); py++) { if (x >= 0 && x < image.getWidth()) image.setPixelABGR(x, py, color); if (x + w - 1 >= 0 && x + w - 1 < image.getWidth()) image.setPixelABGR(x + w - 1, py, color); }
        }
    }

    private JsonObject perform(JsonObject params, CancellationToken cancellation) {
        PendingBatch batch = new PendingBatch(params.getAsJsonArray("actions").deepCopy(), cancellation,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(params.has("deadline_ms") ? params.get("deadline_ms").getAsLong() : 30_000),
                params.has("on_failure") && params.get("on_failure").getAsString().equals("continue"));
        onClient(() -> {
            if (minecraft.player == null || replayHandler() != null) throw new BridgeException(BridgeError.INVALID_MODE, "live player control is unavailable");
            if (pendingBatch != null) throw new BridgeException(BridgeError.CONFLICT, "an action batch is already active");
            pendingBatch = batch; return null;
        });
        while (true) {
            try { return batch.result.get(100, TimeUnit.MILLISECONDS); }
            catch (TimeoutException polling) {
                if (cancellation.isCancelled() || System.nanoTime() >= batch.deadlineNanos) minecraft.execute(this::tickActions);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof BridgeException bridge) throw bridge;
                throw new BridgeException(BridgeError.INTERNAL_ERROR, "action batch failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); cancellation.cancel(); throw new BridgeException(BridgeError.CANCELLED, "action batch interrupted");
            }
        }
    }

    void tickActions() {
        tickRecordingFinalization();
        PendingBatch batch = pendingBatch;
        if (batch == null) return;
        try {
            if (batch.token.isCancelled()) throw new BridgeException(BridgeError.CANCELLED, "action batch cancelled");
            if (System.nanoTime() >= batch.deadlineNanos) throw new BridgeException(BridgeError.TIMEOUT, "action batch deadline exceeded");
            if (batch.current != null && System.nanoTime() >= batch.stepDeadlineNanos) throw new BridgeException(BridgeError.TIMEOUT, "action step deadline exceeded");
            if (batch.remainingTicks > 0) {
                updateTimedAction(batch);
                if (--batch.remainingTicks == 0) finishStep(batch);
                return;
            }
            if (batch.index >= batch.actions.size()) { completeBatch(batch); return; }
            beginStep(batch, batch.actions.get(batch.index).getAsJsonObject());
            if (batch.remainingTicks == 0) finishStep(batch);
        } catch (BridgeException failure) { failBatch(batch, failure); }
        catch (RuntimeException failure) { failBatch(batch, new BridgeException(BridgeError.INTERNAL_ERROR, "action batch failed")); }
    }

    private void beginStep(PendingBatch batch, JsonObject action) {
        batch.current = action; batch.stepStartTick = gameTick();
        batch.stepDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(action.has("timeout_ms") ? action.get("timeout_ms").getAsLong() : 30_000L);
        if (action.has("preconditions") && !conditionsMet(action.get("preconditions"))) throw new BridgeException(BridgeError.CONFLICT, "action preconditions were not met");
        String kind = action.get("kind").getAsString().toLowerCase();
        JsonObject progress = new JsonObject(); progress.addProperty("step", batch.index); progress.addProperty("kind", kind); progress.addProperty("status", "started"); events.publish("action.progress", progress);
        int ticks = Math.max(1, action.has("ticks") ? action.get("ticks").getAsInt() :
                Set.of("wait_until", "fly_to", "land", "break_block").contains(kind) ? Math.max(1, (action.has("timeout_ms") ? action.get("timeout_ms").getAsInt() : 30_000) / 50) : 1);
        switch (kind) {
            case "look" -> { batch.startYaw = minecraft.player.getYRot(); batch.startPitch = minecraft.player.getXRot(); batch.targetYaw = action.get("yaw").getAsFloat(); batch.targetPitch = action.get("pitch").getAsFloat(); batch.totalTicks = batch.remainingTicks = ticks; }
            case "turn" -> { batch.startYaw = minecraft.player.getYRot(); batch.startPitch = minecraft.player.getXRot(); batch.targetYaw = batch.startYaw + action.get("yaw").getAsFloat(); batch.targetPitch = batch.startPitch + action.get("pitch").getAsFloat(); batch.totalTicks = batch.remainingTicks = ticks; }
            case "look_at_position" -> lookAt(new Vec3(action.get("x").getAsDouble(), action.get("y").getAsDouble(), action.get("z").getAsDouble()));
            case "look_at_entity" -> lookAt(resolveEntity(action).getEyePosition());
            case "look_at_block" -> lookAt(Vec3.atCenterOf(resolveBlock(action)));
            case "move" -> { axis(action); batch.remainingTicks = ticks; }
            case "jump" -> { set(minecraft.options.keyJump, true); batch.remainingTicks = ticks; }
            case "sprint" -> { set(minecraft.options.keySprint, true); batch.remainingTicks = ticks; }
            case "sneak" -> { set(minecraft.options.keyShift, true); batch.remainingTicks = ticks; }
            case "swim", "fly_move" -> { axis(action); set(minecraft.options.keyJump, action.has("vertical") && action.get("vertical").getAsFloat() > 0); set(minecraft.options.keyShift, action.has("vertical") && action.get("vertical").getAsFloat() < 0); batch.remainingTicks = ticks; }
            case "fly_to" -> { if (!minecraft.player.getAbilities().mayfly) throw new BridgeException(BridgeError.POLICY_DENIED, "flight is not granted"); lookAt(new Vec3(action.get("x").getAsDouble(), action.get("y").getAsDouble(), action.get("z").getAsDouble())); set(minecraft.options.keyUp, true); double dy = action.get("y").getAsDouble() - minecraft.player.getY(); set(minecraft.options.keyJump, dy > 1); set(minecraft.options.keyShift, dy < -1); batch.remainingTicks = ticks; }
            case "ascend" -> { set(minecraft.options.keyJump, true); batch.remainingTicks = ticks; }
            case "descend", "land" -> { set(minecraft.options.keyShift, true); batch.remainingTicks = ticks; }
            case "attack" -> { if (action.has("target_id")) minecraft.gameMode.attack(minecraft.player, resolveEntity(action)); else { set(minecraft.options.keyAttack, true); batch.remainingTicks = ticks; } }
            case "use" -> { if (action.has("state") && action.get("state").getAsString().equals("release")) set(minecraft.options.keyUse, false); else { set(minecraft.options.keyUse, true); batch.remainingTicks = ticks; } }
            case "interact_entity", "mount" -> { Entity entity = resolveEntity(action); minecraft.gameMode.interact(minecraft.player, entity, new EntityHitResult(entity), hand(action)); }
            case "break_block" -> { BlockPos pos = resolveBlock(action); Direction side = direction(action); lookAt(Vec3.atCenterOf(pos)); minecraft.gameMode.startDestroyBlock(pos, side); set(minecraft.options.keyAttack, true); batch.remainingTicks = ticks; }
            case "place_or_use_item" -> { if (hasBlock(action)) { BlockPos pos = resolveBlock(action); Direction side = direction(action); minecraft.gameMode.useItemOn(minecraft.player, hand(action), new BlockHitResult(Vec3.atCenterOf(pos), side, pos, false)); } else minecraft.gameMode.useItem(minecraft.player, hand(action)); }
            case "wait_ticks" -> batch.remainingTicks = action.get("ticks").getAsInt();
            case "wait_until" -> { if (!condition(action)) batch.remainingTicks = ticks; }
            case "set_flying" -> { if (!minecraft.player.getAbilities().mayfly) throw new BridgeException(BridgeError.POLICY_DENIED, "flight is not granted"); minecraft.player.getAbilities().flying = action.get("enabled").getAsBoolean(); }
            case "pick_block" -> tap(minecraft.options.keyPickItem);
            case "swap_offhand" -> tap(minecraft.options.keySwapOffhand);
            case "drop_item" -> tap(minecraft.options.keyDrop);
            case "open_inventory" -> tap(minecraft.options.keyInventory);
            case "close_screen", "cancel" -> { if (minecraft.gui.screen() != null) minecraft.gui.screen().onClose(); }
            case "select_hotbar_slot" -> minecraft.player.getInventory().setSelectedSlot(action.get("slot").getAsInt() - 1);
            case "click_slot" -> minecraft.gameMode.handleContainerInput(minecraft.player.containerMenu.containerId, action.get("slot").getAsInt(), action.has("button") ? action.get("button").getAsInt() : 0, ContainerInput.valueOf(action.has("click_type") ? action.get("click_type").getAsString().toUpperCase() : "PICKUP"), minecraft.player);
            case "click_widget" -> clickWidget(action);
            case "type_text" -> { if (minecraft.gui.screen() == null) throw new BridgeException(BridgeError.INVALID_MODE, "no screen is open"); ((ScreenInvoker) minecraft.gui.screen()).replayMcp$insertText(action.get("text").getAsString(), false); }
            case "submit" -> { if (minecraft.gui.screen() == null) throw new BridgeException(BridgeError.INVALID_MODE, "no screen is open"); minecraft.gui.screen().keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)); }
            case "send_chat" -> minecraft.getConnection().sendChat(action.get("message").getAsString());
            case "execute_command" -> minecraft.getConnection().sendCommand(action.get("command").getAsString());
            case "swing_hand" -> minecraft.player.swing(hand(action));
            case "dismount" -> minecraft.player.stopRiding();
            case "vehicle_input" -> { axis(action); set(minecraft.options.keyJump, action.has("jump") && action.get("jump").getAsBoolean()); if (action.has("dismount") && action.get("dismount").getAsBoolean()) minecraft.player.stopRiding(); batch.remainingTicks = ticks; }
            case "respawn" -> minecraft.player.respawn();
            case "stop_all_inputs" -> releaseHeld();
            case "checkpoint" -> { }
            default -> throw new BridgeException(BridgeError.CAPABILITY_UNAVAILABLE, kind + " requires a target or screen adapter unavailable in the current state");
        }
    }

    private void updateTimedAction(PendingBatch batch) {
        String kind = batch.current.get("kind").getAsString().toLowerCase();
        if (kind.equals("look") || kind.equals("turn")) {
            float progress = (batch.totalTicks - batch.remainingTicks + 1f) / batch.totalTicks;
            minecraft.player.setYRot(batch.startYaw + (batch.targetYaw - batch.startYaw) * progress);
            minecraft.player.setXRot(Math.max(-90, Math.min(90, batch.startPitch + (batch.targetPitch - batch.startPitch) * progress)));
        } else if (kind.equals("wait_until") && condition(batch.current)) batch.remainingTicks = 1;
        else if (kind.equals("fly_to")) {
            Vec3 target = new Vec3(batch.current.get("x").getAsDouble(), batch.current.get("y").getAsDouble(), batch.current.get("z").getAsDouble());
            double tolerance = batch.current.has("tolerance") ? batch.current.get("tolerance").getAsDouble() : 1.0;
            if (minecraft.player.position().distanceTo(target) <= tolerance) batch.remainingTicks = 1;
            else { lookAt(target); double dy = target.y - minecraft.player.getY(); set(minecraft.options.keyJump, dy > tolerance); set(minecraft.options.keyShift, dy < -tolerance); }
        } else if (kind.equals("land") && minecraft.player.onGround()) batch.remainingTicks = 1;
        else if (kind.equals("break_block") && minecraft.level.getBlockState(resolveBlock(batch.current)).isAir()) batch.remainingTicks = 1;
    }

    private boolean condition(JsonObject action) {
        String condition = action.has("condition") ? action.get("condition").getAsString() : "player_present";
        return namedCondition(condition);
    }

    private boolean conditionsMet(JsonElement specification) {
        if (specification == null || specification.isJsonNull()) return true;
        if (specification.isJsonPrimitive()) return namedCondition(specification.getAsString());
        if (specification.isJsonArray()) {
            for (JsonElement condition : specification.getAsJsonArray()) if (!conditionsMet(condition)) return false;
            return true;
        }
        if (!specification.isJsonObject()) throw new BridgeException(BridgeError.INVALID_REQUEST, "condition must be a string, object, or array");
        JsonObject object = specification.getAsJsonObject();
        if (object.has("condition")) return namedCondition(object.get("condition").getAsString());
        for (var entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean()) throw new BridgeException(BridgeError.INVALID_REQUEST, "condition object values must be boolean");
            if (namedCondition(entry.getKey()) != entry.getValue().getAsBoolean()) return false;
        }
        return true;
    }

    private boolean namedCondition(String condition) {
        return switch (condition) {
            case "player_present" -> minecraft.player != null;
            case "on_ground" -> minecraft.player != null && minecraft.player.onGround();
            case "screen_open" -> minecraft.gui.screen() != null;
            case "screen_closed" -> minecraft.gui.screen() == null;
            case "flying" -> minecraft.player != null && minecraft.player.getAbilities().flying;
            case "riding" -> minecraft.player != null && minecraft.player.isPassenger();
            case "in_water" -> minecraft.player != null && minecraft.player.isInWater();
            default -> throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown wait condition");
        };
    }

    private void finishStep(PendingBatch batch) {
        releaseHeld();
        String kind = batch.current.get("kind").getAsString().toLowerCase(java.util.Locale.ROOT);
        if (kind.equals("wait_until") && !condition(batch.current)) throw new BridgeException(BridgeError.TIMEOUT, "wait condition was not met");
        if (kind.equals("fly_to")) {
            Vec3 target = new Vec3(batch.current.get("x").getAsDouble(), batch.current.get("y").getAsDouble(), batch.current.get("z").getAsDouble());
            double tolerance = batch.current.has("tolerance") ? batch.current.get("tolerance").getAsDouble() : 1.0;
            if (minecraft.player.position().distanceTo(target) > tolerance) throw new BridgeException(BridgeError.TIMEOUT, "flight target was not reached");
        }
        if (kind.equals("land") && !minecraft.player.onGround()) throw new BridgeException(BridgeError.TIMEOUT, "player did not land");
        if (kind.equals("break_block") && !minecraft.level.getBlockState(resolveBlock(batch.current)).isAir()) throw new BridgeException(BridgeError.TIMEOUT, "block was not broken");
        if (batch.current.has("postconditions") && !conditionsMet(batch.current.get("postconditions"))) throw new BridgeException(BridgeError.CONFLICT, "action postconditions were not met");
        JsonObject trace = new JsonObject(); trace.addProperty("kind", batch.current.get("kind").getAsString()); trace.addProperty("status", "completed");
        trace.addProperty("start_tick", batch.stepStartTick); trace.addProperty("end_tick", gameTick());
        if (minecraft.player != null) { JsonObject position = new JsonObject(); position.addProperty("x", minecraft.player.getX()); position.addProperty("y", minecraft.player.getY()); position.addProperty("z", minecraft.player.getZ()); trace.add("player_position", position); }
        if (batch.current.get("kind").getAsString().equalsIgnoreCase("checkpoint")) trace.add("snapshot", snapshot());
        if (batch.current.has("label")) trace.add("label", batch.current.get("label")); batch.trace.add(trace); batch.index++; batch.current = null;
        JsonObject progress = new JsonObject(); progress.addProperty("step", batch.index - 1); progress.addProperty("status", "completed"); events.publish("action.progress", progress);
    }

    private void completeBatch(PendingBatch batch) {
        releaseHeld(); pendingBatch = null; JsonObject result = new JsonObject(); result.add("trace", batch.trace); result.add("final_state", snapshot()); batch.result.complete(result); events.publish("action.completed", result.deepCopy());
    }
    private void failBatch(PendingBatch batch, BridgeException failure) {
        releaseHeld();
        if (batch.continueOnFailure && batch.current != null) {
            JsonObject trace = new JsonObject(); trace.addProperty("kind", batch.current.get("kind").getAsString()); trace.addProperty("status", "failed"); trace.addProperty("error", failure.error().wireName()); trace.addProperty("message", failure.getMessage());
            trace.addProperty("start_tick", batch.stepStartTick); trace.addProperty("end_tick", gameTick()); batch.trace.add(trace);
            JsonObject event = trace.deepCopy(); event.addProperty("step", batch.index); events.publish("action.failed", event);
            batch.index++; batch.current = null; batch.remainingTicks = 0; return;
        }
        JsonObject event = new JsonObject(); event.addProperty("step", batch.index); event.addProperty("error", failure.error().wireName()); event.addProperty("message", failure.getMessage()); events.publish("action.failed", event);
        pendingBatch = null; batch.result.completeExceptionally(failure);
    }
    private void releaseHeld() { held.forEach(key -> key.setDown(false)); held.clear(); }
    private long gameTick() { return minecraft.level == null ? 0 : minecraft.level.getGameTime(); }

    private static final class PendingBatch {
        final JsonArray actions; final CancellationToken token; final long deadlineNanos; final boolean continueOnFailure; final CompletableFuture<JsonObject> result = new CompletableFuture<>(); final JsonArray trace = new JsonArray();
        int index, remainingTicks, totalTicks; long stepStartTick, stepDeadlineNanos; JsonObject current; float startYaw, startPitch, targetYaw, targetPitch;
        PendingBatch(JsonArray actions, CancellationToken token, long deadlineNanos, boolean continueOnFailure) { this.actions = actions; this.token = token; this.deadlineNanos = deadlineNanos; this.continueOnFailure = continueOnFailure; }
    }

    private void lookAt(Vec3 target) {
        Vec3 origin = minecraft.player.getEyePosition(); double dx = target.x - origin.x, dy = target.y - origin.y, dz = target.z - origin.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        minecraft.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
        minecraft.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private Entity resolveEntity(JsonObject action) {
        String id = action.has("target_id") ? action.get("target_id").getAsString() : action.has("observation_id") ? action.get("observation_id").getAsString() : null;
        if (id == null) throw new BridgeException(BridgeError.INVALID_REQUEST, "entity target_id is required");
        return entityIds.resolve(id);
    }

    private static boolean hasBlock(JsonObject action) { return action.has("target_id") && action.get("target_id").getAsString().startsWith("block:") || action.has("x") && action.has("y") && action.has("z"); }

    private BlockPos resolveBlock(JsonObject action) {
        if (action.has("target_id")) {
            String[] parts = action.get("target_id").getAsString().split(":");
            if (parts.length == 4 && parts[0].equals("block")) try { return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])); } catch (NumberFormatException ignored) { }
        }
        if (action.has("x") && action.has("y") && action.has("z")) return new BlockPos(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt());
        throw new BridgeException(BridgeError.INVALID_REQUEST, "block coordinates or target_id are required");
    }

    private static Direction direction(JsonObject action) {
        Direction direction = Direction.byName(action.has("face") ? action.get("face").getAsString() : "up");
        if (direction == null) throw new BridgeException(BridgeError.INVALID_REQUEST, "invalid block face"); return direction;
    }

    private static InteractionHand hand(JsonObject action) {
        return action.has("hand") && action.get("hand").getAsString().equalsIgnoreCase("off_hand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    private void clickWidget(JsonObject action) {
        if (minecraft.gui.screen() == null) throw new BridgeException(BridgeError.INVALID_MODE, "no screen is open");
        GuiEventListener child = widgetIds.resolve(action.get("target_id").getAsString());
        if (!(child instanceof AbstractButton button) || !button.active || !button.visible) throw new BridgeException(BridgeError.CONFLICT, "widget is not an active button");
        button.onPress(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
    }

    private void axis(JsonObject action) {
        float forward = action.has("forward") ? action.get("forward").getAsFloat() : 0;
        float strafe = action.has("strafe") ? action.get("strafe").getAsFloat() : 0;
        set(minecraft.options.keyUp, forward > 0); set(minecraft.options.keyDown, forward < 0);
        set(minecraft.options.keyRight, strafe > 0); set(minecraft.options.keyLeft, strafe < 0);
    }
    private void tap(KeyMapping key) { KeyMapping.click(key.getDefaultKey()); }
    private void set(KeyMapping key, boolean down) { key.setDown(down); if (down) held.add(key); else held.remove(key); }

    private JsonObject recordingStatus() {
        return onClient(() -> {
            var handler = ReplayModRecording.instance == null ? null : ReplayModRecording.instance.getConnectionEventHandler();
            var listener = handler == null ? null : handler.getPacketListener();
            JsonObject result = new JsonObject(); result.addProperty("armed", listener != null); result.addProperty("logical_recording", logicalRecording);
            if (listener != null) result.addProperty("duration_us", listener.getCurrentDuration() * 1_000L);
            if (takeId != null) result.addProperty("take_id", takeId);
            if (listener != null) result.addProperty("path", ((PacketListenerAccessor) listener).replayMcp$outputPath().toAbsolutePath().normalize().toString());
            result.addProperty("time_precision_us", 1_000); return result;
        });
    }

    private JsonObject recordingStart(JsonObject params) {
        return onClient(() -> {
            var listener = recordingListener();
            if (listener == null) throw new BridgeException(BridgeError.RECORDING_NOT_ARMED, "Replay Mod recording was not armed when the connection began; enable recording and reconnect");
            if (logicalRecording) throw new BridgeException(BridgeError.CONFLICT, "a logical take is already active");
            takeId = params.has("take_id") ? params.get("take_id").getAsString() : UUID.randomUUID().toString();
            listener.addMarker("Replay MCP take start: " + takeId);
            logicalRecording = true;
            JsonObject result = recordingStatus(); result.addProperty("take_id", takeId);
            events.publish("recording.changed", result.deepCopy()); return result;
        });
    }

    private JsonObject recordingStop() {
        return onClient(() -> {
            var listener = recordingListener();
            if (listener == null) throw new BridgeException(BridgeError.RECORDING_NOT_ARMED, "Replay Mod recording is unavailable");
            if (!logicalRecording) throw new BridgeException(BridgeError.CONFLICT, "no logical take is active");
            listener.addMarker("Replay MCP take split: " + takeId);
            logicalRecording = false;
            JsonObject result = recordingStatus(); result.addProperty("status", "pending_finalization");
            result.addProperty("take_id", takeId); result.addProperty("recoverable", true);
            pendingRecordingPath = ((PacketListenerAccessor) listener).replayMcp$outputPath().toAbsolutePath().normalize();
            pendingRecordingSize = -1; pendingRecordingStableTicks = 0; takeId = null;
            events.publish("recording.changed", result.deepCopy()); return result;
        });
    }

    private void tickRecordingFinalization() {
        Path pending = pendingRecordingPath;
        if (pending == null || recordingListener() != null || !Files.isRegularFile(pending)) return;
        try {
            long size = Files.size(pending);
            if (size != pendingRecordingSize) { pendingRecordingSize = size; pendingRecordingStableTicks = 0; return; }
            if (++pendingRecordingStableTicks < 20) return;
            JsonObject event = new JsonObject(); event.addProperty("status", "finalized"); event.addProperty("recoverable", true);
            event.add("artifact", describeFile(pending, "application/x-minecraft-replay", 0, 0, true));
            events.publish("recording.changed", event); pendingRecordingPath = null; pendingRecordingSize = -1; pendingRecordingStableTicks = 0;
        } catch (IOException failure) {
            JsonObject event = new JsonObject(); event.addProperty("status", "recoverable"); event.addProperty("path", pending.toString());
            events.publish("recording.changed", event); pendingRecordingPath = null;
        }
    }

    private JsonObject marker(JsonObject params) {
        return onClient(() -> {
            var handler = ReplayModRecording.instance == null ? null : ReplayModRecording.instance.getConnectionEventHandler();
            var listener = handler == null ? null : handler.getPacketListener();
            if (listener == null) throw new BridgeException(BridgeError.RECORDING_NOT_ARMED, "Replay Mod recording was not armed when the connection began");
            listener.addMarker(params.get("name").getAsString()); return success();
        });
    }

    private JsonArray replayList() {
        try {
            Path root = replayRoot(); JsonArray result = new JsonArray();
            try (var files = Files.list(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".mcpr")).sorted().forEach(path -> {
                    JsonObject item = new JsonObject(); item.addProperty("name", path.getFileName().toString()); item.addProperty("size", safeSize(path));
                    item.addProperty("path", path.toAbsolutePath().normalize().toString()); result.add(item);
                });
            }
            return result;
        } catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "cannot list replay library"); }
    }

    private JsonObject replayMetadata(JsonObject params) {
        Path source = confinedReplay(params.get("path").getAsString(), true);
        try (ReplayFile file = ReplayMod.instance.files.open(source)) {
            ReplayMetaData metadata = file.getMetaData(); JsonObject result = new JsonObject();
            result.addProperty("path", source.toString()); result.addProperty("duration_us", metadata.getDuration() * 1_000L);
            result.addProperty("created_at_ms", metadata.getDate()); result.addProperty("server", metadata.getServerName()); result.addProperty("minecraft_version", metadata.getMcVersion());
            result.addProperty("file_format", metadata.getFileFormat()); result.addProperty("file_format_version", metadata.getFileFormatVersion()); result.addProperty("time_precision_us", 1_000);
            return result;
        } catch (IOException e) { throw new BridgeException(BridgeError.INVALID_REQUEST, "cannot read replay metadata"); }
    }

    private JsonObject replayOpen(JsonObject params) {
        if (openReplay != null || replayHandler() != null) throw new BridgeException(BridgeError.CONFLICT, "a replay is already open");
        Path source = confinedReplay(params.get("path").getAsString(), true);
        try {
            Path workingRoot = minecraft.gameDirectory.toPath().resolve(".replay-mcp/working").toAbsolutePath().normalize(); Files.createDirectories(workingRoot);
            String base = source.getFileName().toString().replaceAll("\\.mcpr$", "");
            Path working = workingRoot.resolve(base + "-" + UUID.randomUUID() + ".mcpr");
            ReplayFile file = ReplayMod.instance.files.open(source, working);
            onClient(() -> { ReplayModReplay.instance.startReplay(file); return null; });
            openReplay = file; replaySource = source; replayWorkingCopy = working; replayDirty = false;
            JsonObject result = new JsonObject(); result.addProperty("source", source.toString()); result.addProperty("working_copy", working.toString()); result.addProperty("source_immutable", true); return result;
        } catch (IOException e) { throw new BridgeException(BridgeError.INVALID_REQUEST, "cannot open replay"); }
    }

    private JsonObject replayClose() {
        if (replayDirty) throw new BridgeException(BridgeError.CONFLICT, "replay has unsaved timeline changes");
        onClient(() -> { if (replayHandler() != null) replayHandler().endReplay(); return null; });
        clearReplay(); return success();
    }

    private JsonObject replaySave(JsonObject params) {
        if (openReplay == null) throw new BridgeException(BridgeError.INVALID_MODE, "no replay is open");
        String destinationName;
        if (params.has("save_as")) destinationName = params.get("save_as").getAsString();
        else {
            String name = replaySource.getFileName().toString().replaceAll("\\.mcpr$", ""); int n = 1;
            do { destinationName = name + "-edit-" + n++ + ".mcpr"; } while (Files.exists(replayRoot().resolve(destinationName)));
        }
        Path destination = confinedReplay(destinationName, false);
        if (Files.exists(destination) || destination.equals(replaySource)) throw new BridgeException(BridgeError.CONFLICT, "save destination exists or is the immutable source");
        try {
            SPTimeline timeline = ReplayModSimplePathing.instance == null ? null : ReplayModSimplePathing.instance.getCurrentTimeline();
            if (timeline != null) openReplay.writeTimelines(timeline, java.util.Map.of("Replay MCP", timeline.getTimeline()));
            openReplay.saveTo(destination.toFile()); replayDirty = false; JsonObject result = success(); result.addProperty("path", destination.toString()); return result;
        }
        catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "cannot save replay"); }
    }

    private JsonObject playback(JsonObject params) {
        return onClient(() -> {
            var handler = replayHandler(); if (handler == null) throw new BridgeException(BridgeError.INVALID_MODE, "no replay is loaded");
            var sender = handler.getReplaySender(); String operation = params.get("operation").getAsString();
            switch (operation) {
                case "seek" -> { long time = params.get("time_us").getAsLong() / 1_000L; if (time < 0 || time > handler.getReplayDuration()) throw new BridgeException(BridgeError.INVALID_REQUEST, "seek time is outside the replay"); sender.jumpToTime(Math.toIntExact(time)); }
                case "play" -> sender.setReplaySpeed(playbackSpeed(params.has("speed") ? params.get("speed").getAsDouble() : 1.0));
                case "pause" -> sender.setReplaySpeed(0);
                case "speed" -> sender.setReplaySpeed(playbackSpeed(params.get("speed").getAsDouble()));
                case "step" -> { long delta = params.has("delta_us") ? params.get("delta_us").getAsLong() / 1_000L : 50L; sender.jumpToTime((int) Math.max(0, Math.min(handler.getReplayDuration(), sender.currentTimeStamp() + delta))); }
                case "spectate" -> handler.spectateEntity(resolveEntity(params));
                case "detach" -> handler.spectateCamera();
                default -> throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown playback operation");
            }
            JsonObject result = success(); result.addProperty("time_us", sender.currentTimeStamp() * 1_000L); result.addProperty("speed", sender.getReplaySpeed()); return result;
        });
    }

    private static double playbackSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0 || speed > 64) throw new BridgeException(BridgeError.INVALID_REQUEST, "playback speed must be between 0 and 64");
        return speed;
    }

    private synchronized JsonObject renderStart(JsonObject params) {
        if (activeRenderJob != null) throw new BridgeException(BridgeError.CONFLICT, "a Replay Mod render is already active");
        var handler = replayHandler();
        SPTimeline timeline = ReplayModSimplePathing.instance == null ? null : ReplayModSimplePathing.instance.getCurrentTimeline();
        if (handler == null || timeline == null) throw new BridgeException(BridgeError.INVALID_MODE, "an editable replay timeline is required");
        Path output = renderOutput(params.get("output").getAsString());
        if (Files.exists(output)) throw new BridgeException(BridgeError.CONFLICT, "render output already exists");
        boolean sequence = params.has("preset") && params.get("preset").getAsString().equals("transparent_png");
        Path partial = partialOutput(output, sequence);
        RenderSettings settings = renderSettings(params, partial);
        int restoreWidth = minecraft.getWindow().getWidth(), restoreHeight = minecraft.getWindow().getHeight();
        String jobId = UUID.randomUUID().toString(); activeRenderJob = jobId; activeRenderOutput = output; activeRenderPartial = partial; activeRenderCancelled = false;
        ReplayMod.instance.runLaterWithoutLock(() -> {
            MCVer.resizeMainWindow(minecraft, settings.getVideoWidth(), settings.getVideoHeight());
            ReplayMod.instance.runLaterWithoutLock(() -> {
            try {
                VideoRenderer renderer = new VideoRenderer(settings, handler, timeline.getTimeline()); activeRenderer = renderer;
                if (activeRenderCancelled) renderer.cancel();
                startProgress(renderer, jobId);
                boolean completed = renderer.renderVideo();
                boolean succeeded = completed && !renderer.hasFailed() && !activeRenderCancelled;
                JsonObject event = renderEvent(jobId, renderer); event.addProperty("complete", succeeded);
                if (succeeded) {
                    moveAtomic(partial, output);
                    event.addProperty("output", output.toString());
                    if (Files.isRegularFile(output)) event.add("artifact", describeFile(output, mimeFor(output), settings.getVideoWidth(), settings.getVideoHeight(), true));
                } else {
                    event.addProperty("incomplete_output", partial.toString());
                }
                events.publish(succeeded ? "render.completed" : activeRenderCancelled ? "render.cancelled" : "render.failed", event);
            } catch (Throwable failure) {
                ReplayModRender.LOGGER.error("Replay MCP render job {} failed", jobId, failure);
                JsonObject event = new JsonObject(); event.addProperty("job_id", jobId); event.addProperty("error", "Replay Mod renderer failed");
                event.addProperty("incomplete_output", partial.toString()); events.publish("render.failed", event);
            } finally { MCVer.resizeMainWindow(minecraft, restoreWidth, restoreHeight); clearActiveRender(); }
            });
        });
        JsonObject result = new JsonObject(); result.addProperty("job_id", jobId); result.addProperty("status", "running");
        result.addProperty("output", output.toString()); result.addProperty("partial_output", partial.toString()); return result;
    }

    private synchronized JsonObject renderStill(JsonObject params) {
        if (activeRenderJob != null) throw new BridgeException(BridgeError.CONFLICT, "a Replay Mod render is already active");
        var handler = replayHandler();
        if (handler == null) throw new BridgeException(BridgeError.INVALID_MODE, "a replay must be loaded for still rendering");
        if (!params.has("time_us")) throw new BridgeException(BridgeError.INVALID_REQUEST, "time_us is required");
        long requestedUs = params.get("time_us").getAsLong();
        long actualUs = Math.floorDiv(requestedUs, 1_000L) * 1_000L;
        if (requestedUs < 0 || actualUs / 1_000L > handler.getReplayDuration()) throw new BridgeException(BridgeError.INVALID_REQUEST, "still time is outside the replay");
        Path output = renderOutput(params.get("output").getAsString());
        if (Files.exists(output)) throw new BridgeException(BridgeError.CONFLICT, "still output already exists");
        Path frames = partialOutput(output, true);
        int width = params.has("width") ? params.get("width").getAsInt() : 1920;
        int height = params.has("height") ? params.get("height").getAsInt() : 1080;
        boolean alpha = params.has("alpha") && params.get("alpha").getAsBoolean();
        boolean nameTags = !params.has("name_tags") || params.get("name_tags").getAsBoolean();
        int aa = params.has("anti_aliasing") ? params.get("anti_aliasing").getAsInt() : 4;
        JsonObject normalized = params.deepCopy(); normalized.addProperty("preset", "transparent_png"); normalized.addProperty("fps", 10);
        normalized.addProperty("width", width); normalized.addProperty("height", height); normalized.addProperty("alpha", alpha);
        normalized.addProperty("name_tags", nameTags); normalized.addProperty("anti_aliasing", aa);
        RenderSettings settings = renderSettings(normalized, frames);
        int restoreWidth = minecraft.getWindow().getWidth(), restoreHeight = minecraft.getWindow().getHeight();
        var camera = awaitReplayCamera(handler);
        if (camera == null) throw new BridgeException(BridgeError.INVALID_MODE, "Replay Mod camera is unavailable");
        SPTimeline oneFrame = new SPTimeline();
        oneFrame.setDefaultInterpolatorType(InterpolatorType.LINEAR);
        int replayMs = Math.toIntExact(actualUs / 1_000L);
        for (long outputMs : new long[]{0L, 100L}) {
            oneFrame.addTimeKeyframe(outputMs, replayMs + (outputMs == 0 ? 0 : 1));
            oneFrame.addPositionKeyframe(outputMs, camera.getX(), camera.getY(), camera.getZ(), camera.getYRot(), camera.getXRot(), camera.roll, -1);
        }
        String jobId = UUID.randomUUID().toString(); activeRenderJob = jobId; activeRenderOutput = output; activeRenderPartial = frames; activeRenderCancelled = false;
        ReplayMod.instance.runLaterWithoutLock(() -> {
            MCVer.resizeMainWindow(minecraft, settings.getVideoWidth(), settings.getVideoHeight());
            ReplayMod.instance.runLaterWithoutLock(() -> {
            try {
                VideoRenderer renderer = new VideoRenderer(settings, handler, oneFrame.getTimeline()); activeRenderer = renderer;
                if (activeRenderCancelled) renderer.cancel();
                boolean completed = renderer.renderVideo();
                boolean succeeded = completed && !renderer.hasFailed() && !activeRenderCancelled;
                JsonObject event = renderEvent(jobId, renderer); event.addProperty("complete", succeeded); event.addProperty("time_us", actualUs);
                if (succeeded) {
                    Path frame = frames.resolve("0.png");
                    if (!Files.isRegularFile(frame)) throw new IOException("Replay Mod did not produce the expected still frame");
                    moveAtomic(frame, output); Files.deleteIfExists(frames);
                    event.add("artifact", describeFile(output, "image/png", width, height, true));
                    events.publish("render.completed", event);
                } else {
                    event.addProperty("incomplete_output", frames.toString());
                    events.publish(activeRenderCancelled ? "render.cancelled" : "render.failed", event);
                }
            } catch (Throwable failure) {
                ReplayModRender.LOGGER.error("Replay MCP still render job {} failed", jobId, failure);
                JsonObject event = new JsonObject(); event.addProperty("job_id", jobId); event.addProperty("error", "Replay Mod still renderer failed");
                event.addProperty("incomplete_output", frames.toString()); events.publish("render.failed", event);
            } finally { MCVer.resizeMainWindow(minecraft, restoreWidth, restoreHeight); clearActiveRender(); }
            });
        });
        JsonObject result = new JsonObject(); result.addProperty("job_id", jobId); result.addProperty("status", "running");
        result.addProperty("output", output.toString()); result.addProperty("time_us", actualUs); result.addProperty("time_precision_us", 1_000); return result;
    }

    private com.replaymod.replay.camera.CameraEntity awaitReplayCamera(com.replaymod.replay.ReplayHandler handler) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var camera = handler.getCameraEntity();
            if (camera != null) return camera;
            if (minecraft.isSameThread()) return null;
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new BridgeException(BridgeError.CANCELLED, "still render interrupted while waiting for replay camera"); }
        } while (System.nanoTime() < deadline);
        return null;
    }

    private synchronized JsonObject renderCancel(JsonObject params) {
        if (activeRenderJob == null || (params.has("job_id") && !params.get("job_id").getAsString().equals(activeRenderJob))) {
            throw new BridgeException(BridgeError.CONFLICT, "render job is not active");
        }
        activeRenderCancelled = true; if (activeRenderer != null) activeRenderer.cancel();
        JsonObject result = new JsonObject(); result.addProperty("cancelled", true); result.addProperty("job_id", activeRenderJob);
        result.addProperty("incomplete_output", activeRenderPartial.toString()); return result;
    }

    private void startProgress(VideoRenderer renderer, String jobId) {
        renderMonitor.scheduleAtFixedRate(() -> {
            if (renderer == activeRenderer) events.publish("render.progress", renderEvent(jobId, renderer));
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    private static JsonObject renderEvent(String jobId, VideoRenderer renderer) {
        JsonObject event = new JsonObject(); event.addProperty("job_id", jobId); event.addProperty("frames_done", renderer.getFramesDone()); event.addProperty("total_frames", renderer.getTotalFrames());
        event.addProperty("progress", renderer.getTotalFrames() == 0 ? 0 : (double) renderer.getFramesDone() / renderer.getTotalFrames()); return event;
    }

    private synchronized void clearActiveRender() {
        activeRenderer = null; activeRenderJob = null; activeRenderOutput = null; activeRenderPartial = null; activeRenderCancelled = false;
    }

    private static Path partialOutput(Path output, boolean directory) {
        String name = output.getFileName().toString(); String suffix = "-" + UUID.randomUUID() + ".partial";
        if (!directory) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = "." + name.substring(0, dot) + suffix + name.substring(dot);
            else name = "." + name + suffix;
        } else name = "." + name + suffix;
        return output.resolveSibling(name);
    }

    private static String mimeFor(Path output) {
        String name = output.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".webm") ? "video/webm" : name.endsWith(".png") ? "image/png" : "video/mp4";
    }

    private static JsonObject describeFile(Path file, String mime, int width, int height, boolean complete) throws IOException {
        JsonObject result = new JsonObject(); Path real = file.toRealPath(); result.addProperty("path", real.toString()); result.addProperty("mime_type", mime);
        if (width > 0) result.addProperty("width", width);
        if (height > 0) result.addProperty("height", height);
        result.addProperty("size", Files.size(real)); result.addProperty("complete", complete);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(real), digest)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
            result.addProperty("sha256", java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    private Path renderOutput(String requested) {
        try {
            Path root = ReplayModRender.instance.getVideoFolder().toPath(); Files.createDirectories(root); root = root.toRealPath();
            Path output = root.resolve(requested).normalize().toAbsolutePath();
            if (!output.startsWith(root) || output.equals(root)) throw new BridgeException(BridgeError.POLICY_DENIED, "render output escapes Replay Mod render root");
            Files.createDirectories(output.getParent());
            if (!output.getParent().toRealPath().startsWith(root)) throw new BridgeException(BridgeError.POLICY_DENIED, "render output escapes Replay Mod render root");
            return output;
        } catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "cannot prepare render output"); }
    }

    private static RenderSettings renderSettings(JsonObject p, Path output) {
        String preset = p.has("preset") ? p.get("preset").getAsString() : "high_quality";
        int width = p.has("width") ? p.get("width").getAsInt() : preset.equals("preview") ? 1280 : 1920;
        int height = p.has("height") ? p.get("height").getAsInt() : preset.equals("preview") ? 720 : 1080;
        int fps = p.has("fps") ? p.get("fps").getAsInt() : preset.equals("preview") ? 30 : 60;
        int bitrate = (p.has("bitrate_kbps") ? p.get("bitrate_kbps").getAsInt() : preset.equals("preview") ? 6_000 : 30_000) * 1024;
        RenderSettings.EncodingPreset encoding = preset.equals("transparent_png") ? RenderSettings.EncodingPreset.PNG : RenderSettings.EncodingPreset.MP4_CUSTOM;
        RenderSettings.RenderMethod method = switch (p.has("method") ? p.get("method").getAsString() : "default") {
            case "stereoscopic" -> RenderSettings.RenderMethod.STEREOSCOPIC;
            case "cubemap" -> RenderSettings.RenderMethod.CUBIC;
            case "equirectangular" -> RenderSettings.RenderMethod.EQUIRECTANGULAR;
            case "ods" -> RenderSettings.RenderMethod.ODS;
            default -> RenderSettings.RenderMethod.DEFAULT;
        };
        int aa = p.has("anti_aliasing") ? p.get("anti_aliasing").getAsInt() : 1;
        RenderSettings.AntiAliasing antiAliasing = switch (aa) { case 2 -> RenderSettings.AntiAliasing.X2; case 4 -> RenderSettings.AntiAliasing.X4; case 8 -> RenderSettings.AntiAliasing.X8; default -> RenderSettings.AntiAliasing.NONE; };
        boolean stabilize = p.has("stabilization") && p.get("stabilization").getAsBoolean();
        return new RenderSettings(method, encoding, width, height, fps, bitrate, output.toFile(), !p.has("name_tags") || p.get("name_tags").getAsBoolean(),
                p.has("alpha") && p.get("alpha").getAsBoolean(), stabilize, stabilize, stabilize, null, 360, 180, false, false, false,
                antiAliasing, "", encoding.getValue(), false);
    }

    private com.replaymod.replay.ReplayHandler replayHandler() { return ReplayModReplay.instance == null ? null : ReplayModReplay.instance.getReplayHandler(); }
    private com.replaymod.recording.packet.PacketListener recordingListener() { var handler = ReplayModRecording.instance == null ? null : ReplayModRecording.instance.getConnectionEventHandler(); return handler == null ? null : handler.getPacketListener(); }
    private Path replayRoot() { try { return ReplayMod.instance.folders.getReplayFolder().toRealPath(); } catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "cannot resolve Replay Mod replay root"); } }
    private Path confinedReplay(String relative, boolean existing) {
        if (relative == null || relative.isBlank()) throw new BridgeException(BridgeError.INVALID_REQUEST, "replay path is required");
        Path root = replayRoot(); Path candidate = root.resolve(relative).normalize().toAbsolutePath();
        if (!candidate.startsWith(root) || candidate.equals(root)) throw new BridgeException(BridgeError.POLICY_DENIED, "replay path escapes Replay Mod root");
        if (existing && !Files.isRegularFile(candidate)) throw new BridgeException(BridgeError.INVALID_REQUEST, "replay does not exist"); return candidate;
    }
    private void clearReplay() { openReplay = null; replaySource = null; replayWorkingCopy = null; replayDirty = false; }
    private static long safeSize(Path path) { try { return Files.size(path); } catch (IOException ignored) { return -1; } }
    private static void moveAtomic(Path source, Path target) throws IOException {
        try { Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(source, target); }
    }
    private static JsonObject at(JsonObject tracks, String name, long timeUs) {
        if (!tracks.has(name)) return null;
        JsonObject best = null; for (JsonElement element : tracks.getAsJsonArray(name)) { JsonObject frame = element.getAsJsonObject(); if (frame.get("time_us").getAsLong() <= timeUs) best = frame; else break; } return best;
    }
    private static double number(JsonObject object, String key, double fallback) { return object != null && object.has(key) ? object.get(key).getAsDouble() : fallback; }
    private static JsonObject success() { JsonObject result = new JsonObject(); result.addProperty("ok", true); return result; }

    private <T> T onClient(java.util.concurrent.Callable<T> task) {
        if (minecraft.isSameThread()) { try { return task.call(); } catch (BridgeException e) { throw e; } catch (Exception e) { throw new BridgeException(BridgeError.INTERNAL_ERROR, "client operation failed"); } }
        CompletableFuture<T> future = new CompletableFuture<>();
        minecraft.execute(() -> { try { future.complete(task.call()); } catch (Throwable e) { future.completeExceptionally(e); } });
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { if (e.getCause() instanceof BridgeException bridge) throw bridge; throw new BridgeException(BridgeError.INTERNAL_ERROR, "client operation failed"); }
        catch (java.util.concurrent.TimeoutException e) { throw new BridgeException(BridgeError.TIMEOUT, "client thread deadline exceeded"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new BridgeException(BridgeError.CANCELLED, "operation interrupted"); }
    }

    @Override public synchronized void close() {
        activeRenderCancelled = true;
        if (activeRenderer != null) activeRenderer.cancel();
        renderMonitor.shutdownNow();
        releaseAllInputs();
    }
}
