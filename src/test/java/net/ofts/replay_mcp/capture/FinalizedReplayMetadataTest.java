package net.ofts.replay_mcp.capture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class FinalizedReplayMetadataTest {
 @TempDir Path root;
 void zip(Path path,String markers)throws Exception {
  try(var zip=new ZipOutputStream(Files.newOutputStream(path))) {
   zip.putNextEntry(new ZipEntry("metaData.json"));zip.write("{\"duration\":7000,\"date\":123,\"serverName\":\"test\",\"mcversion\":\"26.2\",\"fileFormat\":\"MCPR\",\"fileFormatVersion\":14}".getBytes());zip.closeEntry();
   if(markers!=null){zip.putNextEntry(new ZipEntry("markers.json"));zip.write(markers.getBytes());zip.closeEntry();}
  }
 }
 @Test void readingOpenSourceIgnoresWorkingCopyMarkers()throws Exception {
  var source=root.resolve("source.mcpr");var working=root.resolve("working.mcpr");
  zip(source,"[{\"realTimestamp\":2,\"value\":{\"name\":\"second\"}},{\"realTimestamp\":1,\"value\":{\"name\":\"first\"}}]");
  var before=FinalizedReplayMetadata.read(source);
  try(var held=new ZipFile(source.toFile())) {
   zip(working,"[]");
   assertEquals(before,FinalizedReplayMetadata.read(source));
   assertEquals(2,before.getAsJsonArray("markers").size());
   assertEquals(1000,before.getAsJsonArray("markers").get(0).getAsJsonObject().get("time_us").getAsLong());
   assertEquals(7000000,before.get("duration_us").getAsLong());
  }
  assertEquals(before,FinalizedReplayMetadata.read(source));
 }
 @Test void missingMarkersAreEmptyAndMalformedMarkersFail()throws Exception {
  var source=root.resolve("source.mcpr");zip(source,null);
  assertEquals(0,FinalizedReplayMetadata.read(source).getAsJsonArray("markers").size());
  zip(source,"[{}]");assertThrows(IOException.class,()->FinalizedReplayMetadata.read(source));
 }
}
