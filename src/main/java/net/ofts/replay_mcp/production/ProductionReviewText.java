package net.ofts.replay_mcp.production;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Player-facing wording derived from the contract; never changes approval policy. */
public final class ProductionReviewText {
    private ProductionReviewText() { }
    public enum Page {
        PLAN("Plan"), CHANGES("Changes"), REQUEST("Your request"), COMMENTS("Comments");
        public final String label;
        Page(String label) { this.label = label; }
    }
    private static final Set<String> FIELDS = Set.of("version", "original_request", "fps", "target_frames",
            "runtime_tolerance", "min_shot_frames", "max_shot_frames", "preferred_min_frames",
            "preferred_max_frames", "min_shots", "max_shots", "reuse_budget_frames", "require_collision",
            "width", "height", "mechanical_requirements", "subjective_criteria");

    public static boolean supported(JsonObject state) {
        JsonObject draft = object(state, "draft");
        return !draft.isEmpty() && number(draft, "version", 1) == 1 && FIELDS.containsAll(draft.keySet());
    }

    public static boolean approved(JsonObject state) {
        return state.has("draft_hash") && state.get("draft_hash").equals(state.get("locked_hash"));
    }

    public static String status(JsonObject state) {
        if (!state.has("draft")) return "No plan to review yet";
        if (!supported(state)) return "This plan needs a newer review screen";
        return approved(state) ? "Plan approved" : state.has("locked_hash") ? "Changes need your approval" : "Waiting for your approval";
    }

    public static List<String> paragraphs(JsonObject state, Page page) {
        JsonObject draft = object(state, "draft");
        List<String> out = new ArrayList<>();
        if (draft.isEmpty()) return List.of("No video plan is available yet. Ask the assistant to propose one.");
        switch (page) {
            case PLAN -> {
                if (!supported(state)) out.add("This plan includes settings this screen cannot explain. Update the review screen before approving it.");
                summary(draft).forEach((label, value) -> out.add(label + ": " + value));
                out.add("Approve plan accepts these requirements. It does not approve the finished video.");
            }
            case REQUEST -> out.add(text(state, "original_request", text(draft, "original_request", "No request recorded.")));
            case CHANGES -> {
                JsonObject previous = object(state, "locked_contract");
                String comparison = "Compared with your approved plan:";
                if (previous.isEmpty()) {
                    var history = state.has("draft_history") ? state.getAsJsonArray("draft_history") : null;
                    previous = history != null && !history.isEmpty() ? history.get(history.size() - 1).getAsJsonObject() : new JsonObject();
                    comparison = "Compared with the previous proposal:";
                }
                if (previous.isEmpty()) out.add("This is the first proposal. Read the Plan page before approving.");
                else {
                    Map<String, String> old = summary(previous), current = summary(draft);
                    out.add(comparison);
                    current.forEach((label, value) -> {
                        if (!value.equals(old.get(label))) out.add(label + "\nBefore: " + old.getOrDefault(label, "Not specified") + "\nNow: " + value);
                    });
                    if (out.size() == 1) out.add("No changes to the settings shown on the Plan page.");
                }
                if (!supported(state)) out.add("Some settings cannot be described by this version. Approval is unavailable.");
            }
            case COMMENTS -> {
                out.add("Suggest a change below. The assistant can revise the plan; sending a comment does not approve or change it.");
                if (!state.has("comments") || state.getAsJsonArray("comments").isEmpty()) out.add("No comments yet.");
                else for (JsonElement entry : state.getAsJsonArray("comments")) {
                    JsonObject comment = entry.getAsJsonObject();
                    boolean current = comment.has("draft_hash") && comment.get("draft_hash").equals(state.get("draft_hash"));
                    out.add((current ? "On this proposal: " : "On an earlier proposal: ") + text(comment, "text", ""));
                }
            }
        }
        return List.copyOf(out);
    }

    public static List<String> exportParagraphs(JsonObject state) {
        List<String> out = new ArrayList<>();
        checks(state, object(state, "locked_contract"), out);
        return List.copyOf(out);
    }

    public static boolean canOverride(JsonObject state) {
        JsonObject report = object(state, "report");
        return report.has("binding") && report.has("artifact_sha256") && state.has("locked_hash")
                && state.get("locked_hash").equals(report.get("contract_hash"))
                && Set.of("FAIL", "INCOMPLETE").contains(text(report, "status", ""))
                && hasItems(report, "findings")
                && !report.get("binding").equals(object(state, "override").get("binding"));
    }

    private static Map<String, String> summary(JsonObject c) {
        Map<String, String> rows = new LinkedHashMap<>();
        double fps = number(c, "fps", 0), target = number(c, "target_frames", 0), tolerance = number(c, "runtime_tolerance", .2);
        rows.put("Video length", duration(target, fps) + ". Allowed: "
                + duration(Math.ceil(target * (1 - tolerance)), fps) + " to " + duration(Math.floor(target * (1 + tolerance)), fps) + ".");
        rows.put("Shot pacing", "Prefer " + duration(number(c, "preferred_min_frames", 3 * fps), fps) + " to "
                + duration(number(c, "preferred_max_frames", 10 * fps), fps) + " per shot.");
        rows.put("Shot limits", "Every shot must last " + duration(number(c, "min_shot_frames", fps), fps) + " to "
                + duration(number(c, "max_shot_frames", 15 * fps), fps) + ".");
        double reuse = number(c, "reuse_budget_frames", 0);
        rows.put("Repeated footage", reuse == 0 ? "Not allowed." : "Up to " + duration(reuse, fps) + " allowed.");
        String count = !c.has("min_shots") && !c.has("max_shots") ? "No required count."
                : c.has("min_shots") && c.has("max_shots") ? text(c, "min_shots", "") + " to " + text(c, "max_shots", "") + " shots."
                : c.has("min_shots") ? "At least " + text(c, "min_shots", "") + " shots." : "At most " + text(c, "max_shots", "") + " shots.";
        rows.put("Number of shots", count);
        rows.put("Picture", text(c, "width", "?") + " x " + text(c, "height", "?") + ", " + text(c, "fps", "?") + " frames per second.");
        rows.put("Camera clearance", c.has("require_collision") && c.get("require_collision").getAsBoolean()
                ? "Must be checked to stay clear of solid blocks." : "A block-clearance check is not required for approval.");
        rows.put("Additional requirements", list(c, "mechanical_requirements", "None.")
                + (hasItems(c, "mechanical_requirements") ? "\nAutomatic checks for these requirements are not yet supported; completion will remain incomplete." : ""));
        rows.put("Style goals", list(c, "subjective_criteria", "None specified.")
                + (hasItems(c, "subjective_criteria") ? "\nThese are creative goals, not guaranteed by automatic checks." : ""));
        return rows;
    }

    private static void checks(JsonObject state, JsonObject draft, List<String> out) {
        JsonObject report = object(state, "report");
        if (report.isEmpty()) { out.add("No finished video has been checked yet. There are no export exceptions to accept."); return; }
        if (report.has("artifact_path")) {
            String path = report.get("artifact_path").getAsString().replace('\\', '/');
            out.add("Video: " + path.substring(path.lastIndexOf('/') + 1));
        }
        boolean current = state.has("locked_hash") && state.get("locked_hash").equals(report.get("contract_hash"));
        if (!current) out.add("These results belong to an older plan. Ask the assistant to check the current video again.");
        String status = text(report, "status", "INCOMPLETE");
        out.add(switch (status) {
            case "PASS" -> "The supported automatic checks passed. This does not guarantee artistic quality.";
            case "FAIL" -> "The video does not meet all requirements. The assistant should fix the issues below.";
            case "PLAYER_OVERRIDDEN" -> "You accepted exceptions for this export. The failed checks are still recorded.";
            default -> "Some checks or evidence are missing. The video is not verified as complete.";
        });
        if (state.has("override") && object(state, "override").get("binding") != null
                && object(state, "override").get("binding").equals(report.get("binding"))) out.add("You have already accepted the listed exceptions for this export.");
        if (report.has("total_frames")) out.add("Video length: " + duration(number(report, "total_frames", 0), number(draft, "fps", 0)) + ".");
        if (report.has("findings")) for (JsonElement item : report.getAsJsonArray("findings")) {
            JsonObject finding = item.getAsJsonObject();
            String prefix = "advisory".equals(text(finding, "kind", "")) ? "Suggestion: " : "Needs attention: ";
            String location = finding.has("clip") ? "Clip " + (finding.get("clip").getAsInt() + 1) + " — " : "";
            out.add(prefix + location + findingText(finding));
        }
        out.add("Accept exceptions allows this specific export despite these issues. It does not turn failed checks into passed checks.");
    }

    private static String findingText(JsonObject f) {
        return switch (text(f, "rule", "")) {
            case "contract.locked" -> "This version of the plan has not been approved.";
            case "runtime.bounds" -> "The video is shorter or longer than the allowed length.";
            case "shot.bounds" -> "A shot is outside the allowed length limits.";
            case "shot.pacing" -> "A shot is within the hard limits, but outside the preferred pacing.";
            case "shot.count" -> "The number of shots does not match the requested range.";
            case "footage.reuse" -> "The video repeats more footage than allowed.";
            case "collision.required" -> "The required camera clearance check is missing or unfinished.";
            case "render.receipt" -> "A completed, verified source clip is missing.";
            case "render.format" -> "A source clip has the wrong picture size or frame rate.";
            case "edit.range" -> "An edit uses footage beyond the end of a source clip.";
            case "assembly.receipt" -> "A verified final export is missing.";
            case "assembly.stale" -> "The export was made from an earlier plan or edit. Export it again.";
            case "assembly.metadata" -> "The exported video does not match the planned length or picture settings.";
            case "requirement.unsupported" -> "An additional requirement cannot yet be checked automatically. See the Plan page.";
            default -> "A check could not be completed. Ask the assistant for the details.";
        };
    }

    public static String duration(double frames, double fps) {
        if (!Double.isFinite(frames) || !Double.isFinite(fps) || fps <= 0) return "not specified";
        double seconds = frames / fps;
        String value = BigDecimal.valueOf(seconds).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return value + (seconds == 1 ? " second" : " seconds");
    }
    private static boolean hasItems(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray() && !o.getAsJsonArray(key).isEmpty(); }
    private static String list(JsonObject o, String key, String fallback) {
        if (!hasItems(o, key)) return fallback;
        List<String> items = new ArrayList<>(); for (JsonElement e : o.getAsJsonArray(key)) items.add(e.getAsString());
        return String.join("; ", items);
    }
    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }
    private static String text(JsonObject o, String key, String fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback; }
    private static double number(JsonObject o, String key, double fallback) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback; }
}
