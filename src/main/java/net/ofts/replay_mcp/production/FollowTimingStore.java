package net.ofts.replay_mcp.production;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;
import java.io.IOException;
import java.nio.file.*;

/** Follow provenance is durable and independent of replaceable collision results. */
public final class FollowTimingStore {
    private final Path root;
    public FollowTimingStore(Path root) { this.root=root; }
    private Path file(String source,String path) {
        if(!source.matches("[0-9a-f]{64}")||!path.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid source/path hash");
        return root.resolve(source+"-"+path+".json");
    }
    public synchronized void bind(String source,String path,int fps,long start,long end)throws IOException {
        if(fps<=0||start<0||end<=start)throw new IllegalArgumentException("invalid follow timing");
        var value=new JsonObject();value.addProperty("version",1);value.addProperty("source",source);value.addProperty("path",path);
        value.addProperty("fps",fps);value.addProperty("start_us",start);value.addProperty("end_us",end);
        Path destination=file(source,path);Files.createDirectories(root);Path temporary=Files.createTempFile(root,"binding-",".tmp");
        try { Files.writeString(temporary,value.toString());Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    public synchronized void requireCompatible(String source,String path,int fps,long start,long end)throws IOException {
        Path file=file(source,path);if(Files.notExists(file))return;
        JsonObject value;
        try { value=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(value.get("version").getAsInt()!=1||!source.equals(value.get("source").getAsString())||!path.equals(value.get("path").getAsString()))throw new IllegalArgumentException("binding identity mismatch");
            if(value.get("fps").getAsInt()!=fps||value.get("start_us").getAsLong()!=start||value.get("end_us").getAsLong()!=end)
                throw new BridgeException(BridgeError.CONFLICT,"follow timing is bound to complete plate range and FPS; regenerate or trim in assembly");
        } catch(BridgeException conflict) {throw conflict;}
        catch(RuntimeException malformed) {throw new IOException("invalid persisted follow timing",malformed);}
    }
}
