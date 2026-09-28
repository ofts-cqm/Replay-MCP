package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Ephemeral UI evidence, deliberately separate from durable player authority. */
public final class ReviewPresentation {
    public interface Surface {
        boolean visible(String project, String hash);
        String busyReason();
        void open(String project, String hash);
    }
    private static final class Request {
        final String project, hash;
        final BooleanSupplier authorized;
        String state = "pending", reason = "awaiting_client_presentation";
        Request(String project, String hash, BooleanSupplier authorized) { this.project = project; this.hash = hash; this.authorized = authorized; }
    }
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private final Surface surface;
    private final String identityField;
    public ReviewPresentation(Surface surface) { this(surface, "draft_hash"); }
    public ReviewPresentation(Surface surface, String identityField) { this.surface = surface; this.identityField = identityField; }
    public void cancel(String project) { requests.remove(project); }

    public void request(String project, String hash, BooleanSupplier authorized) {
        requests.put(project, new Request(project, hash, authorized));
        advance();
    }
    public boolean pending() { return requests.values().stream().anyMatch(r -> r.state.equals("pending")); }
    /** Called on client frames, not world ticks: a paused replay may never tick. */
    public void advance() {
        for (Request r : requests.values()) {
            if (r.state.equals("visible") && !surface.visible(r.project, r.hash)) { r.state = "closed"; r.reason = "screen_closed_or_replaced"; }
            if (!r.state.equals("pending")) continue;
            if (!r.authorized.getAsBoolean()) { r.state = "blocked"; r.reason = "director_lease_lost"; continue; }
            if (surface.visible(r.project, r.hash)) { r.state = "visible"; r.reason = "displayed"; continue; }
            String busy = surface.busyReason();
            if (busy != null) { r.reason = busy; continue; }
            try {
                surface.open(r.project, r.hash);
                r.state = surface.visible(r.project, r.hash) ? "visible" : "blocked";
                r.reason = r.state.equals("visible") ? "displayed" : "screen_not_presented";
            } catch (RuntimeException failure) { r.state = "blocked"; r.reason = "presentation_failed"; }
        }
    }
    public JsonObject status(String project, String hash) {
        Request r = requests.get(project);
        JsonObject result = new JsonObject(); result.addProperty("project_id", project); result.addProperty(identityField, hash);
        // Read the actual surface even for manually opened review. Never open a window from a status read.
        if (surface.visible(project, hash)) { result.addProperty("state", "visible"); result.addProperty("reason", "displayed"); }
        else if (r == null || !r.hash.equals(hash)) { result.addProperty("state", "not_requested"); result.addProperty("reason", "no_presentation_request_in_this_client_session"); }
        else {
            if (r.state.equals("visible")) { r.state = "closed"; r.reason = "screen_closed_or_replaced"; }
            result.addProperty("state", r.state); result.addProperty("reason", r.reason);
        }
        return result;
    }
}
