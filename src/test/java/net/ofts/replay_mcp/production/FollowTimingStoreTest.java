package net.ofts.replay_mcp.production;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.ofts.replay_mcp.protocol.BridgeException;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class FollowTimingStoreTest {
 @TempDir Path root;
 private final String source="a".repeat(64),path="b".repeat(64);
 @Test void survivesReloadAndIndependentCollisionReplacement()throws Exception {
  new FollowTimingStore(root).bind(source,path,60,0,3000000);
  var reloaded=new FollowTimingStore(root);
  // Collision cache changes have no access to these durable movement requirements.
  for(String collision:new String[]{"verified","unverified","skipped"}) {
   assertThrows(BridgeException.class,()->reloaded.requireCompatible(source,path,20,0,3000000),collision);
   assertThrows(BridgeException.class,()->reloaded.requireCompatible(source,path,60,1000000,3000000),collision);
  }
  assertDoesNotThrow(()->reloaded.requireCompatible(source,path,60,0,3000000));
  assertDoesNotThrow(()->reloaded.requireCompatible("c".repeat(64),path,20,0,2000000));
  reloaded.bind(source,path,20,0,3000000);
  assertDoesNotThrow(()->new FollowTimingStore(root).requireCompatible(source,path,20,0,3000000));
 }
 @Test void corruptedBindingCannotRemoveGuard()throws Exception {
  new FollowTimingStore(root).bind(source,path,60,0,3000000);
  Files.writeString(root.resolve(source+"-"+path+".json"),"{}");
  assertThrows(IOException.class,()->new FollowTimingStore(root).requireCompatible(source,path,60,0,3000000));
 }
}
