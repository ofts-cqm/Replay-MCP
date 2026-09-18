package net.ofts.replay_mcp.security;

import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathPolicy {
    private final Path root;

    public PathPolicy(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    public Path root() { return root; }

    public Path resolveForWrite(String relative) {
        if (relative == null || relative.isBlank()) {
            throw denied("output path is required");
        }
        Path candidate = root.resolve(relative).normalize().toAbsolutePath();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw denied("path escapes allowed root");
        }
        Path parent = candidate.getParent();
        try {
            Files.createDirectories(parent);
            if (!parent.toRealPath().startsWith(root)) throw denied("path escapes allowed root");
        } catch (IOException e) {
            throw denied("cannot prepare output path");
        }
        return candidate;
    }

    public Path requireExisting(String relative) {
        Path candidate = resolveForWrite(relative);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) throw denied("path escapes allowed root");
            return real;
        } catch (IOException e) {
            throw denied("path does not exist");
        }
    }

    private static BridgeException denied(String message) {
        return new BridgeException(BridgeError.POLICY_DENIED, message);
    }
}
