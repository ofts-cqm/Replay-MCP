package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContractReviewBatchTest {
    @TempDir Path root;
    private JsonObject contract() {
        var result = new JsonObject(); result.addProperty("original_request", "film"); return result;
    }
    @Test void secondVideoReviewExcludesApprovedFirstVideoAndOtherPendingPlans() throws Exception {
        var store = new ProductionReviewStore(root.resolve("second-video.json"));
        String first = store.draft("first", contract(), "").get("draft_hash").getAsString();
        store.physicalSubmit("first", first, "approve-first", "", true);
        var approvedFirst = store.get("first");
        String second = store.draft("second", contract(), "").get("draft_hash").getAsString();
        store.draft("unrelated", contract(), "");
        var session = new ContractReviewSession(store.all(), "second");
        assertEquals(java.util.List.of("second"), session.projectIds());
        assertEquals(java.util.Map.of("second", second), session.hashes());
        assertFalse(session.contains("first", first));
        assertTrue(session.canApprove());
        store.physicalApproveAll(session.hashes(), "second", "approve-second", "");
        assertEquals(approvedFirst, store.get("first"));
        assertTrue(ProductionReviewText.approved(store.get("second")));
        assertFalse(store.get("unrelated").has("locked_hash"));
    }
    @Test void manualReviewShowsOnlyPendingRevisionsAndPreservesHistory() throws Exception {
        Path file = root.resolve("pending.json");
        var store = new ProductionReviewStore(file);
        String first = store.draft("first", contract(), "").get("draft_hash").getAsString();
        store.physicalSubmit("first", first, "approve-first", "", true);
        var approvedFirst = store.get("first");
        store.draft("second", contract(), "");
        assertEquals(java.util.List.of("second"), new ContractReviewSession(new ProductionReviewStore(file).all()).projectIds());
        assertEquals(approvedFirst, store.get("first"));
        var revised = contract(); revised.addProperty("target_frames", 360);
        String revision = store.draft("first", revised, first).get("draft_hash").getAsString();
        var session = new ContractReviewSession(store.all());
        assertEquals(java.util.List.of("first", "second"), session.projectIds());
        assertTrue(session.contains("first", revision));
        assertEquals(first, session.snapshot("first").get("locked_hash").getAsString());
    }
    @Test void manualReviewWithOnlyApprovedPlansIsEmptyButExplicitInspectionIsAllowed() throws Exception {
        var store = new ProductionReviewStore(root.resolve("approved-only.json"));
        String hash = store.draft("first", contract(), "").get("draft_hash").getAsString();
        store.physicalSubmit("first", hash, "approve-first", "", true);
        var session = new ContractReviewSession(store.all());
        assertTrue(session.projectIds().isEmpty()); assertFalse(session.canApprove());
        var explicit = new ContractReviewSession(store.all(), "first");
        assertTrue(explicit.contains("first", hash)); assertFalse(explicit.canApprove());
        assertTrue(new ContractReviewSession(store.all(), "missing").projectIds().isEmpty());
    }
    @Test void browsingKeepsOriginalRequestVisibleAndFreezesBatch() throws Exception {
        var store = new ProductionReviewStore(root.resolve("reviews.json"));
        String a = store.draft("a", contract(), "").get("draft_hash").getAsString();
        store.draft("b", contract(), "");
        var session = new ContractReviewSession(store.all());
        var presentation = new ReviewPresentation(new ReviewPresentation.Surface() {
            public boolean visible(String id, String hash) { return session.contains(id, hash); }
            public String busyReason() { return "player_review_open"; }
            public void open(String id, String hash) { fail("Already in review window"); }
        });
        presentation.request("a", a, () -> true);
        for (String selected : session.projectIds()) {
            session.snapshot(selected); presentation.advance();
            assertEquals("visible", presentation.status("a", a).get("state").getAsString());
        }
        var revised = contract(); revised.addProperty("target_frames", 360);
        store.draft("a", revised, a); store.draft("later", contract(), "");
        assertEquals(2, session.projectIds().size());
        assertTrue(session.contains("a", a)); assertTrue(session.canApprove());
        session.snapshot("a").addProperty("draft_hash", "mutated-copy");
        assertEquals(a, session.snapshot("a").get("draft_hash").getAsString());
    }
    @Test void approvalPersistsWholeBatchWithCommentOnlyOnCurrentVideo() throws Exception {
        Path file = root.resolve("batch.json"); var store = new ProductionReviewStore(file);
        String a = store.draft("a", contract(), "").get("draft_hash").getAsString();
        String b = store.draft("b", contract(), "").get("draft_hash").getAsString();
        var session = new ContractReviewSession(store.all()); store.draft("later", contract(), "");
        store.physicalApproveAll(session.hashes(), "b", "click", "Nice");
        var restored = new ProductionReviewStore(file);
        assertEquals(a, restored.get("a").get("locked_hash").getAsString());
        assertEquals(b, restored.get("b").get("locked_hash").getAsString());
        assertFalse(restored.get("a").has("comments"));
        assertEquals("Nice", restored.get("b").getAsJsonArray("comments").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(restored.get("later").has("locked_hash"));
        var before = store.all(); store.physicalApproveAll(session.hashes(), "b", "click", "Nice");
        assertEquals(before, store.all());
    }
    @Test void staleMemberRejectsEntireBatch() throws Exception {
        Path file = root.resolve("stale.json"); var store = new ProductionReviewStore(file);
        store.draft("a", contract(), ""); String b = store.draft("b", contract(), "").get("draft_hash").getAsString();
        var session = new ContractReviewSession(store.all());
        var changed = contract(); changed.addProperty("target_frames", 360); store.draft("b", changed, b);
        var before = store.all();
        String persistedBefore = java.nio.file.Files.readString(file);
        assertThrows(RuntimeException.class, () -> store.physicalApproveAll(session.hashes(), "a", "click", ""));
        assertEquals(before, store.all()); assertEquals(persistedBefore, java.nio.file.Files.readString(file));
    }
    @Test void alreadyApprovedPlanAndExportExceptionStayUnchanged() throws Exception {
        var store = new ProductionReviewStore(root.resolve("existing.json"));
        String a = store.draft("a", contract(), "").get("draft_hash").getAsString();
        store.physicalSubmit("a", a, "old", "", true);
        var report = com.google.gson.JsonParser.parseString("{\"binding\":\"artifact\",\"artifact_sha256\":\"sha\",\"status\":\"FAIL\",\"findings\":[{}]}").getAsJsonObject();
        report.addProperty("contract_hash", a); store.report("a", report); store.physicalOverride("a", "artifact");
        var before = store.get("a"); store.draft("b", contract(), "");
        store.physicalApproveAll(new ContractReviewSession(store.all()).hashes(), "b", "new", "");
        assertEquals(before, store.get("a")); assertTrue(store.get("b").has("locked_hash"));
    }
    @Test void unsupportedPlanPreventsBatchApproval() throws Exception {
        var store = new ProductionReviewStore(root.resolve("unsupported.json"));
        store.draft("a", contract(), "");
        var unsupported = contract(); unsupported.addProperty("unknown_setting", true);
        store.draft("b", unsupported, "");
        var session = new ContractReviewSession(store.all()); var before = store.all();
        assertFalse(session.canApprove());
        assertThrows(RuntimeException.class, () -> store.physicalApproveAll(session.hashes(), "a", "click", ""));
        assertEquals(before, store.all());
    }
    @Test void failedSaveRollsBackAllInMemoryApprovals() throws Exception {
        Path file = root.resolve("unwritable.json"); var store = new ProductionReviewStore(file);
        store.draft("a", contract(), ""); store.draft("b", contract(), "");
        var session = new ContractReviewSession(store.all()); var before = store.all();
        java.nio.file.Files.delete(file); java.nio.file.Files.createDirectory(file);
        java.nio.file.Files.writeString(file.resolve("block-replacement"), "fixture");
        assertThrows(RuntimeException.class, () -> store.physicalApproveAll(session.hashes(), "b", "click", ""));
        assertEquals(before, store.all());
    }
}
