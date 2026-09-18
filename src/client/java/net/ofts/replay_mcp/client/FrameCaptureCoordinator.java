package net.ofts.replay_mcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameCaptureCoordinator {
    private record Request(boolean hideGui, CompletableFuture<NativeImage> result) { }
    private static final int MAX_PENDING = 128;
    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<Request> ACTIVE = new AtomicReference<>();

    private FrameCaptureCoordinator() { }

    public static CompletableFuture<NativeImage> request(boolean hideGui) {
        Request request = new Request(hideGui, new CompletableFuture<>());
        if (QUEUE.size() >= MAX_PENDING) throw new IllegalStateException("frame capture queue is full");
        QUEUE.add(request);
        request.result().whenComplete((value, failure) -> {
            QUEUE.remove(request);
            ACTIVE.compareAndSet(request, null);
        });
        return request.result();
    }

    public static boolean hideGuiForCapture() {
        Request request = ACTIVE.get();
        if (request == null) {
            request = QUEUE.peek();
            if (request != null) ACTIVE.compareAndSet(null, request);
            request = ACTIVE.get();
        }
        return request != null && request.hideGui();
    }

    public static void finishFrame(RenderTarget target) {
        Request request = ACTIVE.get();
        if (request == null) {
            request = QUEUE.peek();
            if (request != null) ACTIVE.compareAndSet(null, request);
            request = ACTIVE.get();
        }
        if (request == null || !ACTIVE.compareAndSet(request, null)) return;
        QUEUE.remove(request);
        Request capturedRequest = request;
        Screenshot.takeScreenshot(target, image -> {
            if (!capturedRequest.result().complete(image)) image.close();
        });
    }
}
