package net.ofts.replay_mcp.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.ofts.replay_mcp.production.ProductionReviewText;
import java.util.List;

/** Finished-export results and exceptions only; cannot approve or edit a contract. */
final class ProductionExportReviewScreen extends PlayerReviewScreen {
    ProductionExportReviewScreen(ReplayMcpServiceGraph services) { this(services, ""); }
    ProductionExportReviewScreen(ReplayMcpServiceGraph services, String project) { super(services,"Review export results",project); }
    boolean displays(String project, String binding) {
        return projectId.equals(project) && snapshot.has("report")
                && snapshot.getAsJsonObject("report").has("binding")
                && snapshot.getAsJsonObject("report").get("binding").getAsString().equals(binding);
    }
    @Override protected List<String> paragraphs() { return ProductionReviewText.exportParagraphs(snapshot); }
    @Override protected String subtitle() { return "Review issues with the finished video"; }
    @Override protected int contentTop() { return 48; }
    @Override protected int controlsTop() { return height-85; }
    @Override protected String primaryLabel() { return "Later"; }
    private void decide(boolean accept) {
        String binding=snapshot.getAsJsonObject("report").get("binding").getAsString();
        boolean[] saved={false};
        act(() -> { services.production().physicalExportDecision(projectId,binding,accept); saved[0]=true; }, "Decision saved.");
        if(saved[0]) super.onClose();
    }
    @Override protected void init() {
        super.init();
        int buttonWidth=(width-35)/2;
        Button reject=Button.builder(Component.literal("Reject and revise"),b->decide(false)).bounds(15,controlsTop()+25,buttonWidth,20).build();
        reject.active=ProductionReviewText.canOverride(snapshot);addRenderableWidget(reject);
        Button override = Button.builder(Component.literal("Accept exceptions"), b -> decide(true))
                .bounds(20+buttonWidth,controlsTop()+25,buttonWidth,20).build();
        override.active=ProductionReviewText.canOverride(snapshot);addRenderableWidget(override);
    }
}
