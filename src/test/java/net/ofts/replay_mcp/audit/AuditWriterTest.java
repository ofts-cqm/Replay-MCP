package net.ofts.replay_mcp.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditWriterTest {
    @TempDir Path temp;
    @Test void redactsOnlyStringValuesAndRotates() throws Exception {
        Path path = temp.resolve("audit/log.jsonl");
        try (AuditWriter writer = new AuditWriter(path, 1024, 2, Clock.systemUTC(), List.of("secret-[0-9]+"))) {
            JsonObject details = new JsonObject(); details.addProperty("command", "login secret-123"); details.addProperty("count", 7);
            for (int i = 0; i < 30; i++) writer.append("command", "r" + i, details);
        }
        assertTrue(Files.exists(path.resolveSibling("log.jsonl.1")));
        String line = Files.readAllLines(path).getFirst(); JsonObject parsed = JsonParser.parseString(line).getAsJsonObject();
        assertEquals(7, parsed.getAsJsonObject("details").get("count").getAsInt());
        assertFalse(line.contains("secret-123")); assertTrue(line.contains("[REDACTED]"));
    }
}
