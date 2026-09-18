package net.ofts.replay_mcp.replay;

import com.google.gson.JsonObject;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import net.ofts.replay_mcp.security.PathPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Immutable-source replay session bookkeeping; Replay Mod performs the actual open/save. */
public final class ReplaySession {
    private final PathPolicy replayRoots;
    private final PathPolicy workingRoot;
    private Path source;
    private Path workingCopy;
    private boolean dirty;

    public ReplaySession(Path replayRoot, Path workingRoot) throws IOException {
        replayRoots = new PathPolicy(replayRoot);
        this.workingRoot = new PathPolicy(workingRoot);
    }

    public synchronized JsonObject open(String sourceRelative, String sessionName) throws IOException {
        if (source != null) throw new BridgeException(BridgeError.CONFLICT, "a replay is already open");
        source = replayRoots.requireExisting(sourceRelative);
        workingCopy = workingRoot.resolveForWrite(sessionName + ".mcpr");
        if (Files.exists(workingCopy)) throw new BridgeException(BridgeError.CONFLICT, "working copy already exists");
        Files.copy(source, workingCopy, StandardCopyOption.COPY_ATTRIBUTES);
        dirty = false;
        return status();
    }

    public synchronized void markDirty() { requireOpen(); dirty = true; }

    public synchronized JsonObject save(String saveAs) throws IOException {
        requireOpen();
        Path destination = saveAs == null || saveAs.isBlank() ? versionedSibling(source) : replayRoots.resolveForWrite(saveAs);
        if (destination.equals(source) || Files.exists(destination)) throw new BridgeException(BridgeError.CONFLICT, "save destination already exists or is the source");
        Files.copy(workingCopy, destination);
        dirty = false;
        JsonObject result = status(); result.addProperty("saved_path", destination.toRealPath().toString()); return result;
    }

    public synchronized void close() {
        requireOpen();
        if (dirty) throw new BridgeException(BridgeError.CONFLICT, "replay has unsaved changes");
        source = null; workingCopy = null;
    }

    public synchronized JsonObject status() {
        JsonObject result = new JsonObject(); result.addProperty("open", source != null); result.addProperty("dirty", dirty);
        if (source != null) { result.addProperty("source", source.toString()); result.addProperty("working_copy", workingCopy.toString()); }
        return result;
    }

    private Path versionedSibling(Path original) {
        String file = original.getFileName().toString(); int dot = file.lastIndexOf('.'); String stem = dot < 0 ? file : file.substring(0, dot); String ext = dot < 0 ? "" : file.substring(dot);
        for (int i = 1; i <= 10_000; i++) { Path candidate = replayRoots.resolveForWrite(stem + "-edit-" + i + ext); if (!Files.exists(candidate)) return candidate; }
        throw new BridgeException(BridgeError.CONFLICT, "cannot allocate versioned replay name");
    }
    private void requireOpen() { if (source == null) throw new BridgeException(BridgeError.INVALID_MODE, "no replay is open"); }
}
