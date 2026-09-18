package net.ofts.replay_mcp.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.security.PathPolicy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class RenderPresetRegistry {
    public record Preset(String name, int width, int height, int fps, int bitrateKbps,
                         String method, String container, boolean alpha, int antiAliasing) { }
    private static final Set<String> METHODS = Set.of("default", "stereoscopic", "cubemap", "equirectangular", "ods");
    private final Map<String, Preset> presets = Map.of(
            "preview", new Preset("preview", 1280, 720, 30, 6_000, "default", "mp4", false, 1),
            "high_quality", new Preset("high_quality", 1920, 1080, 60, 30_000, "default", "mp4", false, 4),
            "transparent_png", new Preset("transparent_png", 1920, 1080, 30, 0, "default", "png_sequence", true, 4));
    private final PathPolicy outputs;

    public RenderPresetRegistry(Path outputRoot) throws java.io.IOException { outputs = new PathPolicy(outputRoot); }

    public JsonArray list() {
        JsonArray result = new JsonArray();
        presets.values().stream().sorted(java.util.Comparator.comparing(Preset::name)).forEach(p -> result.add(toJson(p)));
        return result;
    }

    public JsonObject validate(JsonObject request) {
        if (request.has("ffmpeg") || request.has("encoder_args") || request.has("command")) {
            throw new BridgeException(BridgeError.POLICY_DENIED, "arbitrary encoder commands are not accepted");
        }
        String name = request.has("preset") ? request.get("preset").getAsString() : "high_quality";
        Preset base = presets.get(name);
        if (base == null) throw new BridgeException(BridgeError.INVALID_REQUEST, "unknown render preset");
        int width = integer(request, "width", base.width(), 16, 16_384);
        int height = integer(request, "height", base.height(), 16, 16_384);
        int fps = integer(request, "fps", base.fps(), 1, 240);
        int bitrate = integer(request, "bitrate_kbps", base.bitrateKbps(), 0, 500_000);
        int aa = integer(request, "anti_aliasing", base.antiAliasing(), 1, 16);
        if (!Set.of(1, 2, 4, 8).contains(aa)) {
            throw new BridgeException(BridgeError.INVALID_REQUEST, "anti_aliasing must be one of 1, 2, 4, or 8");
        }
        String method = request.has("method") ? request.get("method").getAsString() : base.method();
        if (!METHODS.contains(method)) throw new BridgeException(BridgeError.CAPABILITY_UNAVAILABLE, "unsupported Replay Mod render method");
        if (!request.has("output")) throw new BridgeException(BridgeError.INVALID_REQUEST, "output is required");
        Path output = outputs.resolveForWrite(request.get("output").getAsString());
        JsonObject normalized = new JsonObject();
        normalized.addProperty("preset", name); normalized.addProperty("width", width); normalized.addProperty("height", height);
        normalized.addProperty("fps", fps); normalized.addProperty("bitrate_kbps", bitrate); normalized.addProperty("method", method);
        normalized.addProperty("anti_aliasing", aa); normalized.addProperty("alpha", bool(request, "alpha", base.alpha()));
        normalized.addProperty("name_tags", bool(request, "name_tags", true)); normalized.addProperty("stabilization", bool(request, "stabilization", false));
        normalized.addProperty("output", output.toString()); normalized.addProperty("valid", true);
        return normalized;
    }

    private static JsonObject toJson(Preset p) {
        JsonObject json = new JsonObject(); json.addProperty("name", p.name()); json.addProperty("width", p.width()); json.addProperty("height", p.height());
        json.addProperty("fps", p.fps()); json.addProperty("bitrate_kbps", p.bitrateKbps()); json.addProperty("method", p.method());
        json.addProperty("container", p.container()); json.addProperty("alpha", p.alpha()); json.addProperty("anti_aliasing", p.antiAliasing()); return json;
    }
    private static int integer(JsonObject o, String key, int fallback, int min, int max) { int v = o.has(key) ? o.get(key).getAsInt() : fallback; if (v < min || v > max) throw new BridgeException(BridgeError.INVALID_REQUEST, key + " is out of bounds"); return v; }
    private static boolean bool(JsonObject o, String key, boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
}
