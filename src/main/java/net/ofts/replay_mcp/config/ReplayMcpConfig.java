package net.ofts.replay_mcp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ReplayMcpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean bridgeEnabled = true;
    public boolean commandsEnabled = false;
    public boolean flightAutomationEnabled = true;
    public boolean physicalInputRevokesLease = true;
    public int leaseTtlSeconds = 15;
    public int expectedHeartbeatSeconds = 5;
    public int idleCeilingSeconds = 300;
    public List<String> commandDenyPatterns = new ArrayList<>();
    public List<String> auditRedactionPatterns = new ArrayList<>();

    public static ReplayMcpConfig loadOrCreate(Path gameDir) throws IOException {
        Path path = gameDir.resolve("config/replay_mcp.json");
        if (!Files.exists(path)) {
            ReplayMcpConfig config = new ReplayMcpConfig();
            config.validate();
            config.save(path);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ReplayMcpConfig config = GSON.fromJson(reader, ReplayMcpConfig.class);
            if (config == null) throw new IOException("empty Replay MCP configuration");
            config.validate();
            return config;
        } catch (RuntimeException e) {
            throw new IOException("invalid Replay MCP configuration", e);
        }
    }

    public void save(Path path) throws IOException {
        validate();
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
        moveAtomic(temporary, path);
    }

    public Duration leaseTtl() { return Duration.ofSeconds(leaseTtlSeconds); }
    public Duration idleCeiling() { return Duration.ofSeconds(idleCeilingSeconds); }

    public void validate() {
        if (leaseTtlSeconds < 5 || leaseTtlSeconds > 300
                || expectedHeartbeatSeconds < 1 || expectedHeartbeatSeconds >= leaseTtlSeconds
                || idleCeilingSeconds < leaseTtlSeconds || idleCeilingSeconds > 86_400) {
            throw new IllegalArgumentException("invalid lease timing configuration");
        }
        if (commandDenyPatterns == null || auditRedactionPatterns == null) {
            throw new IllegalArgumentException("policy lists must not be null");
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
