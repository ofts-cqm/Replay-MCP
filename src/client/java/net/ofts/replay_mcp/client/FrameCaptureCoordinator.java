package net.ofts.replay_mcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameCaptureCoordinator {
    private record Request(boolean hideGui, boolean requireWorld, CompletableFuture<NativeImage> result) { }
    private static final int MAX_PENDING = 128;
    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Request> ACTIVE = new AtomicReference<>();

    private FrameCaptureCoordinator() { }

    public static CompletableFuture<NativeImage> request(boolean hideGui) {
        return request(hideGui, false);
    }

    public static CompletableFuture<NativeImage> request(boolean hideGui, boolean requireWorld) {
        Request request = new Request(hideGui, requireWorld, new CompletableFuture<>());
        if (QUEUE.size() >= MAX_PENDING) throw new IllegalStateException("frame capture queue is full");
        QUEUE.add(request);
        request.result().whenComplete((value, failure) -> {
            QUEUE.remove(request);
            ACTIVE.compareAndSet(request, null);
        });
        return request.result();
    }

    public static void beginFrame(boolean rendersWorld) {
        Request request = QUEUE.peek();
        if (request != null && (!request.requireWorld() || rendersWorld)) ACTIVE.compareAndSet(null, request);
    }

    public static boolean hideGuiForCapture() {
        Request request = ACTIVE.get();
        return request != null && request.hideGui();
    }

    public static void finishFrame(RenderTarget target) {
        Request request = ACTIVE.get();
        // A request arriving after GUI extraction must wait for the next frame.
        if (request == null || !ACTIVE.compareAndSet(request, null)) return;
        QUEUE.remove(request);
        Request capturedRequest = request;
        Screenshot.takeScreenshot(target, image -> {
            if (!capturedRequest.result().complete(image)) image.close();
        });
    }
}
