package net.ofts.replay_mcp.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.ofts.replay_mcp.client.FrameCaptureCoordinator;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GuiCaptureMixinTest {
    @Test void cleanCaptureClearsPreviouslyBufferedScreenBeforeCancellingExtraction() throws Exception {
        var state=new GuiRenderState();
        // A buffered draw entry from the previous frame must not be replayed.
        state.addBlitToCurrentLayer(null);
        state.clearColorOverride.set(1,1,1,1);
        var mixin=new GuiCaptureMixin() {};
        var field=GuiCaptureMixin.class.getDeclaredField("guiRenderState");
        field.setAccessible(true);field.set(mixin,state);
        var hook=GuiCaptureMixin.class.getDeclaredMethod("replayMcp$hideGuiForCleanCapture",DeltaTracker.class,boolean.class,boolean.class,CallbackInfo.class);
        hook.setAccessible(true);
        var request=FrameCaptureCoordinator.request(true);
        try {
            FrameCaptureCoordinator.beginFrame(true);
            var callback=new CallbackInfo("extractRenderState",true);
            hook.invoke(mixin,null,true,false,callback);
            assertTrue(callback.isCancelled());
            var count=new AtomicInteger();
            state.forEachElement(element->count.incrementAndGet(),GuiRenderState.TraverseRange.ALL);
            assertEquals(0,count.get());
            assertEquals(0,state.clearColorOverride.w());
        } finally {request.cancel(false);}
    }
}
