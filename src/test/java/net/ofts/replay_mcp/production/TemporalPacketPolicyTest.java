package net.ofts.replay_mcp.production;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TemporalPacketPolicyTest {
 @Test void closedAllowlistRejectsChangingAndUnknownHistory() {
  assertTrue(TemporalPacketPolicy.supported("EntityPosition"));
  for(String packet: new String[]{"BlockChange","MultiBlockChange","ChunkData","UnloadChunk","BlockValue","Explosion","EntityTeleport","EntityMetadata","DestroyEntities","PluginMessage","UnknownPlay","FuturePacket","Bundle"}) assertFalse(TemporalPacketPolicy.supported(packet),packet);
 }
}
