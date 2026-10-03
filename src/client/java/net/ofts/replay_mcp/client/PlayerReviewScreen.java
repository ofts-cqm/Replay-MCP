package net.ofts.replay_mcp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared presentation only. Each concrete screen owns its distinct player decision. */
abstract class PlayerReviewScreen extends Screen {
    protected final ReplayMcpServiceGraph services;
    protected String projectId = "", message = "";
    protected JsonObject snapshot = new JsonObject();
    protected int page;
    private int projectIndex;
    private int projectCount;
    private List<FormattedCharSequence> lines = List.of();
    private final Screen returnScreen;
    protected final net.ofts.replay_mcp.production.ContractReviewSession contractSession;

    PlayerReviewScreen(ReplayMcpServiceGraph services, String title, String selectedProject) {
        this(services, title, selectedProject, null);
    }
    PlayerReviewScreen(ReplayMcpServiceGraph services, String title, String selectedProject,
                       net.ofts.replay_mcp.production.ContractReviewSession contractSession) {
        super(Component.literal(title));
        this.contractSession = contractSession;
        this.services = services;
        returnScreen = net.minecraft.client.Minecraft.getInstance().gui.screen();
        List<String> ids = projectIds();
        if (ids.contains(selectedProject)) projectIndex = ids.indexOf(selectedProject);
        refreshSnapshot();
    }
    private List<String> projectIds() {
        if (contractSession != null) return contractSession.projectIds();
        List<String> ids = new ArrayList<>(services.production().all().keySet());
        Collections.sort(ids);
        return ids;
    }
    protected final void refreshSnapshot() {
        List<String> ids = projectIds(); projectCount = ids.size();
        if (ids.isEmpty()) { projectId = ""; snapshot = new JsonObject(); }
        else { projectIndex = Math.floorMod(projectIndex, ids.size()); projectId = ids.get(projectIndex); snapshot = contractSession == null ? services.production().get(projectId) : contractSession.snapshot(projectId); }
        page = 0;
    }
    protected abstract List<String> paragraphs();
    protected abstract String subtitle();
    protected abstract int contentTop();
    protected abstract int controlsTop();
    protected final int pageSize() { return Math.max(1, (controlsTop() - contentTop() - 20) / 10); }
    private int pageCount() { return Math.max(1, (lines.size() + pageSize() - 1) / pageSize()); }

    @Override protected void init() {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String paragraph : paragraphs()) {
            if (!wrapped.isEmpty()) wrapped.add(Component.literal("").getVisualOrderText());
            wrapped.addAll(font.split(Component.literal(paragraph), Math.max(100, width - 40)));
        }
        lines = List.copyOf(wrapped); page = Math.min(page, pageCount() - 1);
        int y = controlsTop(), buttonWidth = (width - 40) / 3;
        Button previous = Button.builder(Component.literal("Previous page"), b -> { page--; rebuildWidgets(); }).bounds(15,y,buttonWidth,20).build();
        previous.active = page > 0; addRenderableWidget(previous);
        Button next = Button.builder(Component.literal("Next page"), b -> { page++; rebuildWidgets(); }).bounds(20+buttonWidth,y,buttonWidth,20).build();
        next.active = page + 1 < pageCount(); addRenderableWidget(next);
        Button video = Button.builder(Component.literal("Next video"), b -> {
            if (!canChangeProject()) { message="Submit your comment first, or clear it before changing video."; return; }
            projectIndex++; message=""; refreshSnapshot(); onProjectChanged(); rebuildWidgets();
        }).bounds(25+2*buttonWidth,y,buttonWidth,20).build();
        video.active = projectCount > 1; addRenderableWidget(video);
        addRenderableWidget(Button.builder(Component.literal(primaryLabel()), b -> primaryAction()).bounds(width-80,8,65,20).build());
    }
    protected boolean canChangeProject() { return true; }
    protected String primaryLabel() { return "Close"; }
    protected void primaryAction() { onClose(); }
    protected void onProjectChanged() { }
    protected final void act(Runnable action, String success) {
        try { action.run(); message = success; refreshSnapshot(); rebuildWidgets(); }
        catch (RuntimeException failure) {
            message = failure instanceof net.ofts.replay_mcp.protocol.BridgeException bridge
                    && bridge.error() == net.ofts.replay_mcp.protocol.BridgeError.CONFLICT
                    ? "The plan or video changed. Close this screen and ask the assistant to refresh it."
                    : "Could not save your decision. Please try again or ask the assistant for help.";
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0,0,width,height,0xFF141923);
        graphics.fill(10,contentTop()-4,width-10,controlsTop()-6,0xFF202938);
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
        graphics.text(font,title,15,10,0xFFFFFFFF);
        graphics.text(font,subtitle(),15,26,0xFFBFCADC);
        int start = page * pageSize();
        for (int i=0;i<pageSize() && start+i<lines.size();i++) graphics.text(font,lines.get(start+i),20,contentTop()+i*10,0xFFF0F3F8);
        graphics.text(font,"Page " + (page+1) + " of " + pageCount() + (projectCount>1 ? "  |  Video " + (projectIndex+1) + " of " + projectCount : ""),15,controlsTop()-15,0xFFACBBD1);
        List<FormattedCharSequence> status = font.split(Component.literal(message), Math.max(100,width-30));
        for(int i=0;i<Math.min(2,status.size());i++) graphics.text(font,status.get(i),15,height-24+i*10,0xFFFFDF98);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreenAndShow(returnScreen); }
}
