package net.ofts.replay_mcp.capture;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipFile;

/** Read only the immutable ZIP, never ReplayMod's locked mutable working overlay. */
public final class FinalizedReplayMetadata {
    private FinalizedReplayMetadata() { }
    public static JsonObject read(Path source) throws IOException {
        try (var zip = new ZipFile(source.toFile())) {
            var meta = entry(zip,"metaData.json",1_048_576,true).getAsJsonObject();
            var result = new JsonObject();
            result.addProperty("duration_us",Math.multiplyExact(meta.get("duration").getAsLong(),1000L));
            result.add("created_at_ms",meta.get("date")); result.add("server",meta.get("serverName"));
            result.add("minecraft_version",meta.get("mcversion")); result.add("file_format",meta.get("fileFormat"));
            result.add("file_format_version",meta.get("fileFormatVersion"));
            result.addProperty("time_precision_us",1000); result.addProperty("finalized",true); result.addProperty("source_immutable",true);
            var markers = new java.util.ArrayList<JsonObject>();
            for(var raw:entry(zip,"markers.json",8_388_608,false).getAsJsonArray()) {
                var record=raw.getAsJsonObject(); var value=record.getAsJsonObject("value");
                String name=value.has("name")&&!value.get("name").isJsonNull()?value.get("name").getAsString():"";
                var marker=new JsonObject(); marker.addProperty("name",name);
                marker.addProperty("time_us",Math.multiplyExact(record.get("realTimestamp").getAsLong(),1000L));
                ClipMarker.parse(name).ifPresent(parsed->{marker.addProperty("namespace","replay_mcp:clip:v1");marker.addProperty("clip_id",parsed.clipId());marker.addProperty("kind",parsed.kind().name().toLowerCase(java.util.Locale.ROOT));});
                markers.add(marker);
            }
            markers.sort(Comparator.comparingLong((JsonObject marker)->marker.get("time_us").getAsLong()).thenComparing(marker->marker.get("name").getAsString()));
            var array=new JsonArray(); markers.forEach(array::add); result.add("markers",array); return result;
        } catch (RuntimeException malformed) { throw new IOException("invalid metadata or marker format",malformed); }
    }
    private static JsonElement entry(ZipFile zip,String name,int limit,boolean required)throws IOException {
        var entry=zip.getEntry(name);
        if(entry==null){if(required)throw new IOException("missing "+name);return new JsonArray();}
        if(entry.getSize()>limit)throw new IOException(name+" exceeds read limit");
        try(var input=zip.getInputStream(entry)) {
            byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IOException(name+" exceeds read limit");
            return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8));
        }
    }
}
