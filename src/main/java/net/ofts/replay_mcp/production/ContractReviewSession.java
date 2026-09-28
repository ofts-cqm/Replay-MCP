package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Frozen review scope: navigation never dismisses a pending review or silently adds new plans. */
public final class ContractReviewSession {
    private final JsonObject snapshots = new JsonObject();
    private final Map<String, String> hashes = new TreeMap<>();
    public ContractReviewSession(JsonObject projects) {
        for (var entry : projects.entrySet()) {
            var state = entry.getValue().getAsJsonObject();
            if (!state.has("draft") || !state.has("draft_hash")) continue;
            snapshots.add(entry.getKey(), state.deepCopy());
            hashes.put(entry.getKey(), state.get("draft_hash").getAsString());
        }
    }
    public List<String> projectIds() { return List.copyOf(hashes.keySet()); }
    public Map<String, String> hashes() { return Map.copyOf(hashes); }
    public JsonObject snapshot(String project) {
        return snapshots.has(project) ? snapshots.getAsJsonObject(project).deepCopy() : new JsonObject();
    }
    public boolean contains(String project, String hash) { return hash.equals(hashes.get(project)); }
    public boolean canApprove() {
        return !hashes.isEmpty() && hashes.keySet().stream().allMatch(id -> ProductionReviewText.supported(snapshot(id)))
                && hashes.keySet().stream().anyMatch(id -> !ProductionReviewText.approved(snapshot(id)));
    }
}
