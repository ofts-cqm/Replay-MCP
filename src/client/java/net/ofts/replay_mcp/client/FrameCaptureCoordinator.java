package net.ofts.replay_mcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameCaptureCoordinator {
    private record Request(boolean hideGui, CompletableFuture<NativeImage> result) { }
    private static final AtomicReference<Request> PENDING = new AtomicReference<>();

    private FrameCaptureCoordinator() { }

    public static CompletableFuture<NativeImage> request(boolean hideGui) {
        Request request = new Request(hideGui, new CompletableFuture<>());
        if (!PENDING.compareAndSet(null, request)) throw new IllegalStateException("a frame capture is already pending");
        request.result().whenComplete((value, failure) -> PENDING.compareAndSet(request, null));
        return request.result();
    }

    public static boolean hideGuiForCapture() {
        Request request = PENDING.get(); return request != null && request.hideGui();
    }

    public static void finishFrame(RenderTarget target) {
        Request request = PENDING.getAndSet(null);
        if (request != null) Screenshot.takeScreenshot(target, request.result()::complete);
    }
}
