package net.ofts.replay_mcp.observation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import net.ofts.replay_mcp.protocol.BridgeError;
import net.ofts.replay_mcp.protocol.BridgeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpatialMapContractTest {
    @Test
    void alignsNegativeSurfaceBoundsToTheWorldGridWhileKeepingRequestedYAsTheSampledBand() {
        SpatialMapContract.Request request = SpatialMapContract.parse(request("surface", -17, 31, 41, 161, -1, 33, 16), -64, 320);
        assertEquals(new SpatialMapContract.Bounds(-32, 32, 32, 176, -16, 48), request.effective());
        assertEquals(4, request.gridX());
        assertEquals(4, request.gridZ());
        assertEquals(16, request.aggregateCells());
        assertEquals(4_096, request.sourceWork());
    }

    @Test
    void alignsAllVolumeAxesAndUsesTheVolumeWorkBudget() {
        SpatialMapContract.Request request = SpatialMapContract.parse(request("volume", -1, 7, 41, 49, -9, -1, 4), -64, 320);
        assertEquals(new SpatialMapContract.Bounds(-4, 8, 40, 52, -12, 0), request.effective());
        assertEquals(27, request.aggregateCells());
        assertEquals(1_728, request.sourceWork());
    }

    @Test
    void requiresARepresentationSpecificReasonAndParentForLevelTwo() {
        JsonObject missing = request("surface", 0, 8, 48, 96, 0, 8, 2);
        BridgeException missingFailure = assertThrows(BridgeException.class, () -> SpatialMapContract.parse(missing, -64, 320));
        assertEquals(BridgeError.INVALID_REQUEST, missingFailure.error());

        missing.addProperty("refines_map_id", "parent"); missing.addProperty("fallback_reason", "tight_clearance");
        assertThrows(BridgeException.class, () -> SpatialMapContract.parse(missing, -64, 320));

        missing.addProperty("fallback_reason", "adjacent_surfaces");
        SpatialMapContract.Request accepted = SpatialMapContract.parse(missing, -64, 320);
        assertTrue(accepted.fallback());
        assertEquals(SpatialMapContract.FALLBACK_SURFACE, accepted.limits());
    }

    @Test
    void rejectsCellOneBuildHeightOverflowAndIndependentWorkLimitBreaches() {
        SpatialMapContract.Request fullBuildHeight = SpatialMapContract.parse(
                request("surface", 0, 4, -64, 320, 0, 4, 4), -64, 320);
        assertEquals(-64, fullBuildHeight.effective().minY());
        assertEquals(320, fullBuildHeight.effective().maxY());

        JsonObject cellOne = request("surface", 0, 8, 48, 96, 0, 8, 1);
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class,
                () -> SpatialMapContract.parse(cellOne, -64, 320)).error());

        JsonObject height = request("volume", 0, 8, -65, 0, 0, 8, 4);
        assertEquals(BridgeError.INVALID_REQUEST, assertThrows(BridgeException.class,
                () -> SpatialMapContract.parse(height, -64, 320)).error());

        JsonObject huge = request("surface", 0, 2_049, 48, 96, 0, 2_049, 32);
        BridgeException tooLarge = assertThrows(BridgeException.class, () -> SpatialMapContract.parse(huge, -64, 320));
        assertEquals(BridgeError.QUERY_TOO_LARGE, tooLarge.error());
        assertTrue(tooLarge.data().has("max_source_work"));
    }

    @Test
    void capabilityKeepsOrdinaryAndFallbackResolutionSeparate() {
        JsonObject capability = SpatialMapContract.capability();
        assertEquals(4, capability.getAsJsonArray("surface_cell_sizes").size());
        assertEquals(2, capability.getAsJsonArray("surface_fallback_cell_sizes").get(0).getAsInt());
        assertFalse(capability.getAsJsonArray("surface_cell_sizes").contains(new com.google.gson.JsonPrimitive(2)));
        assertEquals(512, capability.getAsJsonObject("limits").getAsJsonObject("volume_fallback").get("max_result_cells").getAsInt());
    }

    private static JsonObject request(String representation, int minX, int maxX, int minY, int maxY,
                                      int minZ, int maxZ, int cellSize) {
        JsonObject bounds = new JsonObject(); bounds.addProperty("min_x", minX); bounds.addProperty("max_x", maxX);
        bounds.addProperty("min_y", minY); bounds.addProperty("max_y", maxY);
        bounds.addProperty("min_z", minZ); bounds.addProperty("max_z", maxZ);
        JsonObject request = new JsonObject(); request.addProperty("representation", representation); request.add("bounds", bounds);
        request.addProperty("cell_size", cellSize); return request;
    }
}
