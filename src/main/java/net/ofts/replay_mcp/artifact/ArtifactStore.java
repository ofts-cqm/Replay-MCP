package net.ofts.replay_mcp.artifact;

import com.google.gson.JsonObject;
import net.ofts.replay_mcp.security.PathPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ArtifactStore {
    private final PathPolicy paths;

    public ArtifactStore(Path root) throws IOException { paths = new PathPolicy(root); }
    public Path allocate(String relative) { return paths.resolveForWrite(relative); }

    public JsonObject describe(Path file, String mimeType, int width, int height, boolean complete) throws IOException {
        Path real = file.toRealPath();
        if (!real.startsWith(paths.root())) throw new SecurityException("artifact path escapes root");
        JsonObject result = new JsonObject();
        result.addProperty("path", real.toString());
        result.addProperty("mime_type", mimeType);
        if (width > 0) result.addProperty("width", width);
        if (height > 0) result.addProperty("height", height);
        result.addProperty("size", Files.size(real));
        result.addProperty("sha256", sha256(real));
        result.addProperty("complete", complete);
        return result;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

}
