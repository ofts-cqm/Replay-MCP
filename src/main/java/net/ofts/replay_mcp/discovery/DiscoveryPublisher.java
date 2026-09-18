package net.ofts.replay_mcp.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HexFormat;
import java.util.Set;

public final class DiscoveryPublisher implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path instanceDirectory;
    private final Path descriptor;
    private final byte[] token;

    public DiscoveryPublisher(Path gameDir, InstanceDescriptor metadata, byte[] token) throws IOException {
        if (token.length != 32) throw new IllegalArgumentException("token must contain 256 bits");
        this.token = token.clone();
        this.instanceDirectory = gameDir.resolve(".replay-mcp/instances").resolve(metadata.instanceId());
        this.descriptor = instanceDirectory.resolve("bridge.json");
        Files.createDirectories(instanceDirectory);
        writeProtected(instanceDirectory.resolve("token"), HexFormat.of().formatHex(token));
        writeJsonAtomic(descriptor, metadata);
    }

    public byte[] token() { return token.clone(); }
    public Path descriptor() { return descriptor; }

    private static void writeProtected(Path path, String content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.US_ASCII);
        setOwnerOnly(temporary);
        moveAtomic(temporary, path);
        setOwnerOnly(path);
    }

    private static void writeJsonAtomic(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        }
        moveAtomic(temporary, path);
    }

    private static void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setReadable(false, false)
                    || !path.toFile().setWritable(false, false)
                    || !path.toFile().setReadable(true, true)
                    || !path.toFile().setWritable(true, true)) {
                throw new IOException("cannot protect token permissions");
            }
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(descriptor);
        Files.deleteIfExists(instanceDirectory.resolve("token"));
        try { Files.deleteIfExists(instanceDirectory); } catch (java.nio.file.DirectoryNotEmptyException ignored) { }
    }
}
