package net.ofts.replay_mcp.production;

import com.google.gson.*;
import net.ofts.replay_mcp.protocol.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;

/** Authority lives in the mod. None of the physical* methods has an RPC route. */
public final class ProductionReviewStore {
    private final Path file;
    private JsonObject projects = new JsonObject();
    public ProductionReviewStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) projects = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
    public synchronized JsonObject all() { return projects.deepCopy(); }
    public synchronized JsonObject get(String id) { return projects.has(id) ? projects.getAsJsonObject(id).deepCopy() : new JsonObject(); }
    public synchronized JsonObject draft(String id, JsonObject contract, String expected) {
        JsonObject p = get(id);
        String current = p.has("draft_hash") ? p.get("draft_hash").getAsString() : "";
        if (!current.equals(expected)) throw conflict("draft changed; refresh before revising");
        if (p.has("original_request") && !p.get("original_request").equals(contract.get("original_request"))) throw conflict("original request is immutable");
        if (!contract.has("original_request")) throw conflict("original request is required");
        // An explicit re-presentation request must not manufacture another revision/history entry.
        if (p.has("draft") && p.get("draft").equals(contract)) return p;
        p.add("original_request", contract.get("original_request").deepCopy());
        JsonArray history = p.has("draft_history") ? p.getAsJsonArray("draft_history") : new JsonArray();
        if (p.has("draft")) history.add(p.get("draft").deepCopy());
        JsonObject previous=p.has("draft")?p.getAsJsonObject("draft"):new JsonObject(); JsonArray changes=new JsonArray();
        java.util.TreeSet<String> keys=new java.util.TreeSet<>(previous.keySet()); keys.addAll(contract.keySet());
        for(String key:keys) if(!java.util.Objects.equals(previous.get(key),contract.get(key))) { JsonObject change=new JsonObject(); change.addProperty("field",key); change.add("before",previous.has(key)?previous.get(key).deepCopy():JsonNull.INSTANCE); change.add("after",contract.has(key)?contract.get(key).deepCopy():JsonNull.INSTANCE); changes.add(change); }
        p.add("draft_changes",changes); p.add("draft_history", history); p.add("draft", contract.deepCopy());
        p.addProperty("draft_hash", CanonicalJson.sha256(contract));
        projects.add(id,p); save(); return p.deepCopy();
    }
    public synchronized void physicalComment(String id, String draftHash, String text) {
        JsonObject p = requireDraft(id,draftHash);
        JsonArray comments = p.has("comments") ? p.getAsJsonArray("comments") : new JsonArray();
        JsonObject comment = new JsonObject(); comment.addProperty("draft_hash",draftHash); comment.addProperty("text",text); comment.addProperty("at",Instant.now().toString()); comment.addProperty("authority","physical_player"); comments.add(comment);
        p.add("comments",comments); projects.add(id,p); save();
    }
    /** One physical Submit commits the complete decision and its durable notification together. */
    public synchronized void physicalSubmit(String id, String draftHash, String submissionId, String text, boolean approve) {
        JsonObject p = submitted(requireDraft(id, draftHash), draftHash, submissionId, text, approve);
        JsonObject previous = projects.deepCopy(); projects.add(id, p);
        try { save(); } catch (RuntimeException failure) { projects = previous; throw failure; }
    }
    /** Exact displayed revisions only; one atomic write prevents partial batch approval. */
    public synchronized void physicalApproveAll(java.util.Map<String, String> displayed, String commentProject,
                                               String submissionId, String text) {
        if (displayed.isEmpty() || !displayed.containsKey(commentProject)) throw conflict("no displayed plans");
        JsonObject next = projects.deepCopy();
        for (var entry : displayed.entrySet()) {
            JsonObject p = requireDraft(entry.getKey(), entry.getValue());
            if (!ProductionReviewText.supported(p)) throw conflict("plan cannot be explained by this review screen");
            String comment = entry.getKey().equals(commentProject) ? text : "";
            if (ProductionReviewText.approved(p) && comment.isBlank()) continue;
            next.add(entry.getKey(), submitted(p, entry.getValue(), submissionId, comment, true));
        }
        JsonObject previous = projects; projects = next;
        try { save(); } catch (RuntimeException failure) { projects = previous; throw failure; }
    }
    private JsonObject submitted(JsonObject p, String draftHash, String submissionId, String text, boolean approve) {
        if (p.has("review_submission") && p.getAsJsonObject("review_submission").get("id").getAsString().equals(submissionId)) return p;
        if (text.isBlank() && !approve) throw conflict("add a comment or select approval before submitting");
        String at = Instant.now().toString();
        long sequence = p.has("review_submission_sequence") ? p.get("review_submission_sequence").getAsLong() + 1 : 1;
        JsonObject event = new JsonObject();
        event.addProperty("id", submissionId); event.addProperty("sequence", sequence);
        event.addProperty("draft_hash", draftHash); event.addProperty("at", at);
        event.addProperty("authority", "physical_player"); event.addProperty("approved", approve);
        event.addProperty("comment", text);
        if (!text.isBlank()) {
            JsonArray comments = p.has("comments") ? p.getAsJsonArray("comments") : new JsonArray();
            JsonObject comment = new JsonObject(); comment.addProperty("draft_hash", draftHash);
            comment.addProperty("text", text); comment.addProperty("at", at); comment.addProperty("authority", "physical_player");
            comments.add(comment); p.add("comments", comments);
        }
        if (approve && !ProductionReviewText.approved(p)) {
            p.add("locked_contract", p.get("draft").deepCopy()); p.addProperty("locked_hash", draftHash);
            p.addProperty("locked_at", at); p.remove("override");
            JsonArray history = p.has("lock_history") ? p.getAsJsonArray("lock_history") : new JsonArray();
            JsonObject entry = new JsonObject(); entry.addProperty("hash", draftHash);
            entry.addProperty("at", at); entry.addProperty("authority", "physical_player");
            history.add(entry); p.add("lock_history", history);
        }
        p.addProperty("review_submission_sequence", sequence); p.add("review_submission", event);
        return p;
    }
    public synchronized void physicalLock(String id, String draftHash) {
        JsonObject p = requireDraft(id,draftHash);
        p.add("locked_contract",p.get("draft").deepCopy()); p.addProperty("locked_hash",draftHash);
        p.addProperty("locked_at",Instant.now().toString()); p.remove("override");
        JsonArray history = p.has("lock_history") ? p.getAsJsonArray("lock_history") : new JsonArray();
        JsonObject entry = new JsonObject(); entry.addProperty("hash",draftHash); entry.addProperty("at",Instant.now().toString()); entry.addProperty("authority","physical_player"); history.add(entry); p.add("lock_history",history);
        projects.add(id,p); save();
    }
    public synchronized JsonObject report(String id, JsonObject report) {
        JsonObject p = get(id);
        if (!p.has("locked_hash") || !p.get("locked_hash").equals(report.get("contract_hash"))) throw conflict("report contract is not locked");
        if (!report.has("binding") || !report.has("findings") || !report.has("artifact_sha256")) throw conflict("report requires binding, failures and exact artifact");
        if (!p.has("report") || !p.getAsJsonObject("report").equals(report)) p.remove("override");
        p.add("report",report.deepCopy()); projects.add(id,p); save(); return p.deepCopy();
    }
    public synchronized void physicalOverride(String id, String binding) {
        physicalExportDecision(id,binding,true);
    }
    public synchronized void physicalExportDecision(String id, String binding, boolean accept) {
        JsonObject p = get(id);
        if (!p.has("report")) throw conflict("no export report");
        JsonObject report = p.getAsJsonObject("report");
        if (!report.get("binding").getAsString().equals(binding) || !p.get("locked_hash").equals(report.get("contract_hash"))) throw conflict("export changed");
        if (!report.has("status") || report.get("status").getAsString().equals("PASS") || report.getAsJsonArray("findings").isEmpty()) throw conflict("no failures to override");
        String at = Instant.now().toString();
        if (accept) {
            JsonObject override = report.deepCopy(); override.addProperty("authority","physical_player"); override.addProperty("at",at);
            JsonArray history=p.has("override_history")?p.getAsJsonArray("override_history"):new JsonArray(); history.add(override.deepCopy()); p.add("override_history",history);
            p.add("override",override);
        } else p.remove("override");
        long sequence=p.has("export_decision_sequence")?p.get("export_decision_sequence").getAsLong()+1:1;
        JsonObject decision=new JsonObject(); decision.addProperty("sequence",sequence); decision.addProperty("binding",binding);
        decision.addProperty("decision",accept?"accepted":"rejected"); decision.addProperty("at",at); decision.addProperty("authority","physical_player");
        p.addProperty("export_decision_sequence",sequence); p.add("export_decision",decision);
        JsonArray decisions=p.has("export_decision_history")?p.getAsJsonArray("export_decision_history"):new JsonArray(); decisions.add(decision.deepCopy()); p.add("export_decision_history",decisions);
        JsonObject previous=projects.deepCopy(); projects.add(id,p);
        try { save(); } catch (RuntimeException failure) { projects=previous; throw failure; }
    }
    private JsonObject requireDraft(String id, String hash) {
        JsonObject p = get(id); if (!p.has("draft_hash") || !p.get("draft_hash").getAsString().equals(hash)) throw conflict("draft changed; review the new revision"); return p;
    }
    private void save() {
        try { Files.createDirectories(file.getParent()); Path tmp = Files.createTempFile(file.getParent(),"review-",".tmp");
            try { Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(projects)); Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(tmp); }
        } catch (IOException e) { throw new BridgeException(BridgeError.INTERNAL_ERROR,"cannot persist player review authority"); }
    }
    private static BridgeException conflict(String message) { return new BridgeException(BridgeError.CONFLICT,message); }
}
