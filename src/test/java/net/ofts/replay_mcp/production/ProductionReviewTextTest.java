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
    private JsonObject exportState(String findings) {
        var state = state();
        state.add("locked_contract", state.get("draft").deepCopy());
        state.addProperty("locked_hash", "hidden-draft-id");
        var report = new JsonObject();
        report.addProperty("contract_hash", "hidden-draft-id");
        report.addProperty("status", "FAIL");
        report.add("findings", JsonParser.parseString(findings));
        state.add("report", report);
        return state;
    }
    @Test void exportNamesViolatedRuleAndShowsLimitsClipRangeAndDiagnostic() {
        var state = exportState("""
            [{"rule":"shot.bounds","kind":"failure","clip":1,"range":[90,105],
              "message":"15 frames outside hard shot bounds."}]
            """);
        var contract = state.getAsJsonObject("locked_contract");
        contract.addProperty("min_shot_frames", 90); contract.addProperty("max_shot_frames", 300);
        // A pending revision must not replace the limits for the checked export.
        state.getAsJsonObject("draft").addProperty("min_shot_frames", 150);
        String checks = String.join("\n", ProductionReviewText.exportParagraphs(state));
        assertTrue(checks.contains("Rule violated: Clip 2 — Shot duration [shot.bounds]"));
        assertTrue(checks.contains("Every shot must last 3 seconds to 10 seconds"));
        assertTrue(checks.contains("Output range: 3 seconds to 3.5 seconds"));
        assertTrue(checks.contains("Shot duration: 0.5 seconds"));
        assertTrue(checks.contains("15 frames outside hard shot bounds"));
        assertFalse(checks.contains("Every shot must last 5 seconds"));
    }
    @Test void unsupportedRequirementsAndArtifactChecksKeepTheirSpecificDetails() {
        var state = exportState("""
            [{"rule":"requirement.unsupported","kind":"missing",
              "message":"No mechanical evidence adapter for: Include both day and night views"},
             {"rule":"requirement.unsupported","kind":"missing",
              "message":"No mechanical evidence adapter for: Show the chandelier interior"},
             {"rule":"render.artifact","kind":"missing","message":"render artifact hash changed"},
             {"rule":"assembly.artifact","kind":"missing","message":"Final artifact is missing or changed."}]
            """);
        String checks = String.join("\n", ProductionReviewText.exportParagraphs(state));
        assertTrue(checks.contains("Missing evidence: Additional requirement [requirement.unsupported]"));
        assertTrue(checks.contains("Include both day and night views"));
        assertTrue(checks.contains("Show the chandelier interior"));
        assertTrue(checks.contains("Source video artifact [render.artifact]"));
        assertTrue(checks.contains("render artifact hash changed"));
        assertTrue(checks.contains("Final video artifact [assembly.artifact]"));
        assertFalse(checks.contains("Rule violated:"));
    }
    @Test void runtimeReuseAndCountShowObservedValuesAndReviewedLimits() {
        var state = exportState("""
            [{"rule":"runtime.bounds","kind":"failure","message":"2400 frames outside 1440–2160."},
             {"rule":"footage.reuse","kind":"failure","message":"60 repeated source frames exceeds budget 30."},
             {"rule":"shot.count","kind":"failure","message":"5 editorial shots outside explicit count bounds."},
             {"rule":"shot.pacing","kind":"advisory","clip":0,"message":"30 frames outside preferred pacing band."}]
            """);
        var report = state.getAsJsonObject("report");
        report.addProperty("total_frames", 2400); report.addProperty("reused_frames", 60); report.addProperty("editorial_shots", 5);
        var contract = state.getAsJsonObject("locked_contract");
        contract.addProperty("reuse_budget_frames", 30); contract.addProperty("max_shots", 4);
        String checks = String.join("\n", ProductionReviewText.exportParagraphs(state));
        assertTrue(checks.contains("Allowed: 48 seconds to 72 seconds"));
        assertTrue(checks.contains("Actual video length: 80 seconds"));
        assertTrue(checks.contains("Up to 1 second allowed"));
        assertTrue(checks.contains("Actual repeated footage: 2 seconds"));
        assertTrue(checks.contains("At most 4 shots"));
        assertTrue(checks.contains("Actual shot count: 5"));
        assertTrue(checks.contains("Suggestion: Clip 1 — Shot pacing [shot.pacing]"));
    }
    @Test void unknownAndLegacyFindingsRemainReadableWithoutInventingDetails() {
        var state = exportState("""
            [{"rule":"future.rule","kind":"failure","message":"The north entrance was not visible."},
             {"rule":"shot.bounds","kind":"failure"}, {}]
            """);
        String checks = String.join("\n", ProductionReviewText.exportParagraphs(state));
        assertTrue(checks.contains("Check result [future.rule]"));
        assertTrue(checks.contains("The north entrance was not visible"));
        assertTrue(checks.contains("Shot duration [shot.bounds]"));
        assertTrue(checks.contains("Unidentified check"));
        assertFalse(checks.contains("null"));
        state.addProperty("locked_hash", "new-contract");
        String stale = String.join("\n", ProductionReviewText.exportParagraphs(state));
        assertTrue(stale.contains("older plan"));
        assertFalse(stale.contains("Requirement:"));
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
