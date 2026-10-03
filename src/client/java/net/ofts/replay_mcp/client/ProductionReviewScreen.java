package net.ofts.replay_mcp.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.ofts.replay_mcp.production.ProductionReviewText;
import java.util.List;

/** Contract proposal and comments only; never displays or authorizes export overrides. */
final class ProductionReviewScreen extends PlayerReviewScreen {
    private ProductionReviewText.Page section = ProductionReviewText.Page.PLAN;
    private EditBox comment;
    private String submissionId = java.util.UUID.randomUUID().toString();
    ProductionReviewScreen(ReplayMcpServiceGraph services) { this(services, ""); }
    ProductionReviewScreen(ReplayMcpServiceGraph services, String project) { super(services,"Review video plan",project, new net.ofts.replay_mcp.production.ContractReviewSession(services.production().all(), project)); }
    private String draftHash() { return snapshot.has("draft_hash") ? snapshot.get("draft_hash").getAsString() : ""; }
    boolean displays(String project, String hash) { return contractSession.contains(project, hash); }
    private String approvalLabel() { return contractSession.projectIds().size() > 1
            ? "Approve all videos (" + contractSession.projectIds().size() + ")" : "Approve plan"; }
    @Override protected List<String> paragraphs() { var result = new java.util.ArrayList<String>();
        if (contractSession.projectIds().isEmpty()) return List.of("No video plans are waiting for approval.");
        result.add(contractSession.projectIds().size() == 1
                ? "Approve plan approves this video's requirements. Comments apply to this video."
                : "Approve all videos approves every pending plan listed in this window (" + contractSession.projectIds().size()
                + " videos). Use Next video to review each. Comments apply only to the video you are viewing.");
        result.addAll(ProductionReviewText.paragraphs(snapshot, section)); return result; }
    @Override protected String subtitle() { return ProductionReviewText.status(snapshot); }
    @Override protected int contentTop() { return 70; }
    @Override protected int controlsTop() { return height - 110; }
    @Override protected void onProjectChanged() { section = ProductionReviewText.Page.PLAN; comment = null; submissionId = java.util.UUID.randomUUID().toString(); }
    @Override protected boolean canChangeProject() { return comment == null || comment.getValue().isBlank(); }
    @Override protected String primaryLabel() { return "Submit"; }
    @Override protected void primaryAction() { submit(false); }
    private void submit(boolean approve) {
        String text = comment == null ? "" : comment.getValue().trim();
        if (!approve && text.isBlank()) { message = "Write a comment, or choose " + approvalLabel() + ". Esc discards unsent input."; return; }
        // Both comment and approval are persisted atomically, only from physical UI input.
        boolean[] saved = {false};
        act(() -> { if (approve) services.production().physicalApproveAll(contractSession.hashes(), projectId, submissionId, text);
            else services.production().physicalSubmit(projectId,draftHash(),submissionId,text,false); saved[0] = true; }, "Submitted.");
        if (saved[0]) super.onClose();
    }
    @Override protected void init() {
        String text = comment == null ? "" : comment.getValue();
        super.init();
        int tabWidth = (width-45)/4, i=0;
        for (ProductionReviewText.Page choice : ProductionReviewText.Page.values()) {
            Button tab = Button.builder(Component.literal(choice.label), b -> { section=choice;page=0;rebuildWidgets(); }).bounds(15+i++*(tabWidth+5),43,tabWidth,20).build();
            tab.active = section != choice; addRenderableWidget(tab);
        }
        int y=controlsTop();
        comment = new EditBox(font,15,y+25,width-30,20,Component.literal("Comment for this video (sent with Submit or " + approvalLabel() + ")"));
        comment.setMaxLength(2000); comment.setHint(Component.literal("Comment... Submit sends; Esc discards.")); comment.setValue(text); addRenderableWidget(comment);
        Button approve = Button.builder(Component.literal(approvalLabel()),
                b -> submit(true)).bounds(15,y+51,width-30,20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        contractSession.projectIds().size() > 1
                        ? "Approves all pending plans listed in this window and closes it. Any comment applies only to the current video."
                        : "Approves this video's plan and closes it. Any comment applies to this video."))).build();
        approve.active = contractSession.canApprove();addRenderableWidget(approve);

    }
}
