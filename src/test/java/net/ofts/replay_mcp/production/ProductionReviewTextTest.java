package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductionReviewTextTest {
    private JsonObject state() {
        return JsonParser.parseString("""
            {"draft_hash":"hidden-draft-id","draft":{
              "version":1,"original_request":"A one-minute tour.","fps":30,"target_frames":1800,
              "runtime_tolerance":0.2,"width":1280,"height":720,"reuse_budget_frames":0,
              "require_collision":false,"mechanical_requirements":[],"subjective_criteria":[]}}
            """).getAsJsonObject();
    }
    @Test void summaryExplainsSecondsAndAuthorityWithoutDumpingInternalState() {
        JsonObject state=state();
        String plan=String.join("\n",ProductionReviewText.paragraphs(state,ProductionReviewText.Page.PLAN));
        assertTrue(plan.contains("60 seconds. Allowed: 48 seconds to 72 seconds"));
        assertTrue(plan.contains("Prefer 3 seconds to 10 seconds"));
        assertTrue(plan.contains("Every shot must last 1 second to 15 seconds"));
        assertTrue(plan.contains("Repeated footage: Not allowed"));
        assertTrue(plan.contains("No required count"));
        assertFalse(plan.contains("hidden-draft-id"));assertFalse(plan.contains("target_frames"));
        assertFalse(plan.contains("Accept exceptions"));
        assertTrue(ProductionReviewText.supported(state));
        assertFalse(ProductionReviewText.approved(state));
    }
    @Test void explicitLimitsAndFrameRoundingAreNotReplacedWithDefaults() {
        var state=state();var draft=state.getAsJsonObject("draft");
        draft.addProperty("fps",24);draft.addProperty("target_frames",241);draft.addProperty("runtime_tolerance",0.1);
        draft.addProperty("min_shot_frames",12);draft.addProperty("max_shot_frames",480);
        draft.addProperty("reuse_budget_frames",48);draft.addProperty("max_shots",4);
        draft.addProperty("require_collision",true);
        String plan=String.join("\n",ProductionReviewText.paragraphs(state,ProductionReviewText.Page.PLAN));
        assertTrue(plan.contains("Allowed: 9.042 seconds to 11.042 seconds"));
        assertTrue(plan.contains("0.5 seconds to 20 seconds"));
        assertTrue(plan.contains("Up to 2 seconds allowed"));assertTrue(plan.contains("At most 4 shots"));
        assertTrue(plan.contains("Must be checked to stay clear"));
    }
    @Test void revisionsCompareAgainstApprovedPlanAndCommentsRemainSeparate() {
        var state=state();var old=state.getAsJsonObject("draft").deepCopy();
        state.add("locked_contract",old);state.addProperty("locked_hash","old");
        state.getAsJsonObject("draft").addProperty("target_frames",3600);
        state.add("comments",JsonParser.parseString("[{\"draft_hash\":\"old\",\"text\":\"Make it longer\"}]"));
        String changes=String.join("\n",ProductionReviewText.paragraphs(state,ProductionReviewText.Page.CHANGES));
        assertTrue(changes.contains("Before: 60 seconds"));assertTrue(changes.contains("Now: 120 seconds"));
        assertEquals("Changes need your approval",ProductionReviewText.status(state));
        String comments=String.join("\n",ProductionReviewText.paragraphs(state,ProductionReviewText.Page.COMMENTS));
        assertTrue(comments.contains("On an earlier proposal: Make it longer"));
        assertEquals("A one-minute tour.",ProductionReviewText.paragraphs(state,ProductionReviewText.Page.REQUEST).getFirst());
    }
    @Test void unknownRequirementsCannotBeHiddenBehindAnApprovalButton() {
        var state=state();state.getAsJsonObject("draft").addProperty("future_rule",true);
        assertFalse(ProductionReviewText.supported(state));
        assertTrue(ProductionReviewText.paragraphs(state,ProductionReviewText.Page.PLAN).getFirst().contains("cannot explain"));
    }
    @Test void exportDecisionsRequireCurrentFailedExportAndNeverAppearInPlanPages() {
        var state=state();assertFalse(ProductionReviewText.canOverride(state));
        state.add("locked_contract",state.get("draft").deepCopy());state.addProperty("locked_hash","hidden-draft-id");
        state.add("report",JsonParser.parseString("""
            {"contract_hash":"hidden-draft-id","binding":"export-binding","artifact_sha256":"secret-hash",
             "artifact_path":"/private/output/tour.mp4","status":"FAIL","total_frames":2400,
             "findings":[{"rule":"runtime.bounds","kind":"failure"}]}
            """));
        assertTrue(ProductionReviewText.canOverride(state));
        String checks=String.join("\n",ProductionReviewText.exportParagraphs(state));
        assertTrue(checks.contains("Video: tour.mp4"));assertTrue(checks.contains("shorter or longer"));
        assertFalse(checks.contains("secret-hash"));assertFalse(checks.contains("/private/"));
        for(var page:ProductionReviewText.Page.values()) assertFalse(String.join("\n",ProductionReviewText.paragraphs(state,page)).contains("Accept exceptions"));
        state.add("override",JsonParser.parseString("{\"binding\":\"export-binding\"}"));assertFalse(ProductionReviewText.canOverride(state));
        state.remove("override");state.addProperty("locked_hash","new-contract");assertFalse(ProductionReviewText.canOverride(state));
    }
}
