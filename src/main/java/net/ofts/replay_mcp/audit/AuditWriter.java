package net.ofts.replay_mcp.audit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AuditWriter implements AutoCloseable {
    private final Gson gson = new Gson();
    private final Path path;
    private final long maxBytes;
    private final int rotations;
    private final Clock clock;
    private final List<Pattern> redactions;
    private BufferedWriter writer;

    public AuditWriter(Path path, long maxBytes, int rotations, Clock clock, List<String> redactions) throws IOException {
        if (maxBytes < 1024 || rotations < 1) throw new IllegalArgumentException("invalid audit rotation");
        this.path = path;
        this.maxBytes = maxBytes;
        this.rotations = rotations;
        this.clock = Objects.requireNonNull(clock);
        this.redactions = redactions.stream().map(Pattern::compile).toList();
        Files.createDirectories(path.getParent());
        open();
    }

    public synchronized void append(String type, String requestId, JsonObject details) throws IOException {
        JsonObject event = new JsonObject();
        event.addProperty("timestamp", clock.instant().toString());
        event.addProperty("type", type);
        if (requestId != null) event.addProperty("request_id", requestId);
        event.add("details", redact(details));
        String line = gson.toJson(event);
        if (Files.exists(path) && Files.size(path) + line.length() + 1 > maxBytes) rotate();
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    public String redactText(String text) {
        String result = text;
        for (Pattern pattern : redactions) result = pattern.matcher(result).replaceAll("[REDACTED]");
        return result;
    }

    private JsonObject redact(JsonObject value) {
        return redactElement(value).getAsJsonObject();
    }

    private JsonElement redactElement(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject(); value.getAsJsonObject().entrySet().forEach(e -> result.add(e.getKey(), redactElement(e.getValue()))); return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(e -> result.add(redactElement(e))); return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return new JsonPrimitive(redactText(value.getAsString()));
        return value.deepCopy();
    }

    private void rotate() throws IOException {
        writer.close();
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + "." + rotations));
        for (int i = rotations - 1; i >= 1; i--) {
            Path from = path.resolveSibling(path.getFileName() + "." + i);
            if (Files.exists(from)) Files.move(from, path.resolveSibling(path.getFileName() + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(path)) Files.move(path, path.resolveSibling(path.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        open();
    }

    private void open() throws IOException {
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override public synchronized void close() throws IOException { writer.close(); }
}
