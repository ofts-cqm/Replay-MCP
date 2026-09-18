package net.ofts.replay_mcp.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class FrameCaptureCoordinatorTest {
    @Test void queuesConcurrentRequestsInsteadOfRejectingTheSecondCapture() {
        CompletableFuture<?> first = FrameCaptureCoordinator.request(true);
        CompletableFuture<?> second = assertDoesNotThrow(() -> FrameCaptureCoordinator.request(false));
        first.cancel(false);
        second.cancel(false);
    }
}
