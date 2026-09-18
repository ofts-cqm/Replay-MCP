package net.ofts.replay_mcp.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryPublisherTest {
    @TempDir Path temp;
    @Test void publishesProtectedTokenAndRemovesDescriptor() throws Exception {
        InstanceDescriptor metadata = new InstanceDescriptor("id", 123, 456, "now", "client", temp.toString(), "replay-mcp.bridge/1", "1", "26.2", "26.2-2.6.27");
        Path descriptor;
        try (DiscoveryPublisher publisher = new DiscoveryPublisher(temp, metadata, new byte[32])) {
            descriptor = publisher.descriptor(); assertTrue(Files.exists(descriptor));
            Path token = descriptor.resolveSibling("token"); assertEquals(64, Files.readString(token).length());
            try { assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(token)); }
            catch (UnsupportedOperationException ignored) { }
        }
        assertFalse(Files.exists(descriptor));
    }
}
