package net.ofts.replay_mcp.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class FrameCaptureCoordinatorTest {
    @Test void worldSamplesWaitForAWorldFrameAndDoNotArmAtFrameEnd() {
        var sample=FrameCaptureCoordinator.request(true,true);
        try {
            FrameCaptureCoordinator.beginFrame(false);
            assertFalse(FrameCaptureCoordinator.hideGuiForCapture());
            FrameCaptureCoordinator.finishFrame(null); // no GPU read on a GUI-only frame
            assertFalse(sample.isDone());
            FrameCaptureCoordinator.beginFrame(true);
            assertTrue(FrameCaptureCoordinator.hideGuiForCapture());
        } finally { sample.cancel(false); }
        var late=FrameCaptureCoordinator.request(true);
        try {
            FrameCaptureCoordinator.finishFrame(null); // arrived after frame began
            assertFalse(late.isDone());
            assertFalse(FrameCaptureCoordinator.hideGuiForCapture());
            FrameCaptureCoordinator.beginFrame(false); // menu screenshots still supported
            assertTrue(FrameCaptureCoordinator.hideGuiForCapture());
        } finally { late.cancel(false); }
    }

    @Test void queuesConcurrentRequestsInsteadOfRejectingTheSecondCapture() {
        CompletableFuture<?> first = FrameCaptureCoordinator.request(true);
        CompletableFuture<?> second = assertDoesNotThrow(() -> FrameCaptureCoordinator.request(false));
        first.cancel(false);
        second.cancel(false);
    }
}
