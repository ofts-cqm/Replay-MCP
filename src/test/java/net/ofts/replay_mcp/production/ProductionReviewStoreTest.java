package net.ofts.replay_mcp.production;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class ProductionReviewStoreTest {
 @TempDir Path root;
 @Test void exportRejectionPersistsWithoutOverrideAndCannotTargetStaleReport() throws Exception {
  Path file=root.resolve("export-decisions.json");var store=new ProductionReviewStore(file);
  var contract=JsonParser.parseString("{\"original_request\":\"film\"}").getAsJsonObject();
  String hash=store.draft("p",contract,"").get("draft_hash").getAsString();store.physicalLock("p",hash);
  var report=JsonParser.parseString("{\"binding\":\"export\",\"artifact_sha256\":\"sha\",\"status\":\"FAIL\",\"findings\":[{}]}").getAsJsonObject();report.addProperty("contract_hash",hash);store.report("p",report);
  store.physicalExportDecision("p","export",false);
  var restored=new ProductionReviewStore(file).get("p");
  assertFalse(restored.has("override"));assertEquals("rejected",restored.getAsJsonObject("export_decision").get("decision").getAsString());
  assertEquals(1,restored.get("export_decision_sequence").getAsInt());
  assertThrows(RuntimeException.class,()->store.physicalExportDecision("p","stale",true));
  store.physicalExportDecision("p","export",true);assertTrue(store.get("p").has("override"));
  assertEquals(2,store.get("p").get("export_decision_sequence").getAsInt());
 }
 @Test void submitCommitsCommentAndApprovalTogetherAndSurvivesRestart() throws Exception {
  Path file=root.resolve("submit.json"); var store=new ProductionReviewStore(file);
  var contract=JsonParser.parseString("{\"original_request\":\"one minute\"}").getAsJsonObject();
  String hash=store.draft("p",contract,"").get("draft_hash").getAsString();
  assertFalse(new ProductionReviewStore(file).get("p").has("review_submission"));
  store.physicalSubmit("p",hash,"submit-1","Keep eight shots",true);
  var restored=new ProductionReviewStore(file).get("p");
  assertEquals(hash,restored.get("locked_hash").getAsString());
  assertEquals("Keep eight shots",restored.getAsJsonArray("comments").get(0).getAsJsonObject().get("text").getAsString());
  assertTrue(restored.getAsJsonObject("review_submission").get("approved").getAsBoolean());
  assertEquals(1,restored.get("review_submission_sequence").getAsInt());
  var beforeRetry=store.get("p");
  store.physicalSubmit("p",hash,"submit-1","Keep eight shots",true);
  assertEquals(beforeRetry,store.get("p"));
 }
 @Test void commentSubmissionDoesNotApproveAndRejectsStaleOrEmptyInput() throws Exception {
  var store=new ProductionReviewStore(root.resolve("comments.json"));
  var contract=JsonParser.parseString("{\"original_request\":\"one minute\"}").getAsJsonObject();
  String hash=store.draft("p",contract,"").get("draft_hash").getAsString();
  assertThrows(RuntimeException.class,()->store.physicalSubmit("p",hash,"empty"," ",false));
  store.physicalSubmit("p",hash,"comment","Make eight shots",false);
  assertFalse(store.get("p").has("locked_hash"));
  assertFalse(store.get("p").getAsJsonObject("review_submission").get("approved").getAsBoolean());
  contract.addProperty("target_frames",1800);store.draft("p",contract,hash);
  var before=store.get("p");
  assertThrows(RuntimeException.class,()->store.physicalSubmit("p",hash,"stale","",true));
  assertEquals(before,store.get("p"));
 }
 @Test void identicalDraftReopenDoesNotManufactureHistoryOrAuthority() throws Exception {
  var store=new ProductionReviewStore(root.resolve("reopen.json"));
  var contract=JsonParser.parseString("{\"original_request\":\"one minute\",\"target_frames\":1800}").getAsJsonObject();
  var first=store.draft("p",contract,"");String hash=first.get("draft_hash").getAsString();
  assertEquals(first,store.draft("p",contract,hash));assertFalse(store.get("p").has("locked_hash"));
  assertThrows(RuntimeException.class,()->store.draft("p",contract,"stale"));
  store.physicalLock("p",hash);var locked=store.get("p");
  assertEquals(locked,store.draft("p",contract,hash));assertEquals(0,store.get("p").getAsJsonArray("draft_history").size());
 }
 @Test void physicalAuthorityPersistsAndStaleDecisionsAreRejected() throws Exception {
  Path file=root.resolve("reviews.json");var store=new ProductionReviewStore(file);var contract=JsonParser.parseString("{\"original_request\":\"unique film\",\"target_frames\":5400}").getAsJsonObject();
  var draft=store.draft("project",contract,"");String hash=draft.get("draft_hash").getAsString();assertFalse(draft.has("locked_hash"));
  store.physicalComment("project",hash,"Make it slower");store.physicalLock("project",hash);
  var restored=new ProductionReviewStore(file);assertEquals(hash,restored.get("project").get("locked_hash").getAsString());
  var report=JsonParser.parseString("{\"binding\":\"export-a\",\"artifact_sha256\":\"a\",\"findings\":[{\"rule\":\"runtime.bounds\"}]}").getAsJsonObject();report.addProperty("contract_hash",hash);report.addProperty("status","FAIL");store.report("project",report);
  assertThrows(RuntimeException.class,()->store.physicalOverride("project","old"));store.physicalOverride("project","export-a");assertTrue(store.get("project").has("override"));
  store.report("project",report);assertTrue(store.get("project").has("override"));report.addProperty("artifact_sha256","b");report.addProperty("binding","export-b");store.report("project",report);assertFalse(store.get("project").has("override"));
  contract.addProperty("target_frames",6000);store.draft("project",contract,hash);assertThrows(RuntimeException.class,()->store.physicalLock("project",hash));assertEquals(hash,store.get("project").get("locked_hash").getAsString());
  contract.addProperty("original_request","erased");assertThrows(RuntimeException.class,()->store.draft("project",contract,store.get("project").get("draft_hash").getAsString()));
 }
}
