package net.ofts.replay_mcp.production;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ReviewPresentationTest {
    @Test void exportPresentationUsesExactBindingAndCancellationStopsDeferredPopup() {
        var s = new Surface(); var p = new ReviewPresentation(s, "binding");
        p.request("export-project", "artifact-binding", () -> true);
        var status = p.status("export-project", "artifact-binding");
        assertEquals("visible", status.get("state").getAsString());
        assertEquals("artifact-binding", status.get("binding").getAsString());
        assertFalse(status.has("draft_hash"));
        assertEquals("not_requested", p.status("export-project", "different-export").get("state").getAsString());
        s.busy="render_active";
        p.request("export-project", "new-binding", () -> true);
        assertTrue(p.pending()); p.cancel("export-project"); s.busy=null; p.advance();
        assertFalse(p.pending()); assertEquals(1,s.opens);
    }
    static class Surface implements ReviewPresentation.Surface {
        String project = "", hash = "", busy;
        int opens;
        boolean fails, refuses;
        public boolean visible(String p, String h) { return project.equals(p) && hash.equals(h); }
        public String busyReason() { return busy; }
        public void open(String p, String h) {
            opens++; if (fails) throw new IllegalStateException("unavailable");
            if (!refuses) { project = p; hash = h; }
        }
    }
    @Test void idleRequestOpensExactProjectImmediatelyWithoutWorldTickOrApproval() {
        var s = new Surface(); var p = new ReviewPresentation(s);
        p.request("project-b", "hash-b", () -> true);
        assertEquals(1,s.opens); assertFalse(p.pending());
        assertEquals("visible",p.status("project-b","hash-b").get("state").getAsString());
        assertEquals("not_requested",p.status("project-a","hash-a").get("state").getAsString());
        assertFalse(p.status("project-b","hash-b").has("locked_hash"));
    }
    @Test void renderDefersUntilFrameBoundaryAndStatusDoesNotOpenAnything() {
        var s = new Surface(); s.busy = "render_active"; var p = new ReviewPresentation(s);
        p.request("p","h",()->true); assertTrue(p.pending()); assertEquals(0,s.opens);
        assertEquals("render_active",p.status("p","h").get("reason").getAsString());
        s.busy=null; assertEquals("pending",p.status("p","h").get("state").getAsString()); assertEquals(0,s.opens);
        p.advance(); assertEquals(1,s.opens); assertFalse(p.pending());
    }
    @Test void closureDoesNotStealFocusAndExplicitSameDraftCanReopen() {
        var s = new Surface(); var p = new ReviewPresentation(s); p.request("p","h",()->true);
        s.project=""; s.hash="";
        for(int i=0;i<5;i++) { p.advance(); assertEquals("closed",p.status("p","h").get("state").getAsString()); }
        assertEquals(1,s.opens); p.request("p","h",()->true); assertEquals(2,s.opens);
    }
    @Test void leaseLossBlocksPendingRequestPermanentlyUntilExplicitRequest() {
        var s = new Surface(); s.busy="camera_operation_active"; var alive=new AtomicBoolean(true); var p=new ReviewPresentation(s);
        p.request("p","h",alive::get); alive.set(false); p.advance(); assertFalse(p.pending());
        assertEquals("director_lease_lost",p.status("p","h").get("reason").getAsString());
        s.busy=null; alive.set(true); p.advance(); assertEquals(0,s.opens);
        assertEquals("blocked",p.status("p","h").get("state").getAsString());
    }
    @Test void presentationFailureNeverClaimsVisible() {
        for(boolean throwsFailure : new boolean[]{true,false}) {
            var s=new Surface();s.fails=throwsFailure;s.refuses=!throwsFailure;var p=new ReviewPresentation(s);
            p.request("p","h",()->true); assertEquals("blocked",p.status("p","h").get("state").getAsString());
            p.advance();assertEquals(1,s.opens);
        }
    }
    @Test void activePlayerReviewDefersRevisedDraftAndNeverConfusesHashes() {
        var s=new Surface();var p=new ReviewPresentation(s);p.request("p","old",()->true);s.busy="player_review_open";
        p.request("p","new",()->true);assertTrue(p.pending());assertEquals(1,s.opens);
        assertEquals("pending",p.status("p","new").get("state").getAsString());
        assertEquals("visible",p.status("p","old").get("state").getAsString());
        s.project="";s.hash="";s.busy=null;p.advance();assertEquals(2,s.opens);
        assertEquals("visible",p.status("p","new").get("state").getAsString());
    }
}
