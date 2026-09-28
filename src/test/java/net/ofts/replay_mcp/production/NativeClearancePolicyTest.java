package net.ofts.replay_mcp.production;

import com.replaymod.pathing.properties.CameraProperties;
import com.replaymod.simplepathing.SPTimeline;
import com.replaymod.simplepathing.InterpolatorType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeClearancePolicyTest {
    private SPTimeline linear() {
        var t=new SPTimeline(); t.setDefaultInterpolatorType(InterpolatorType.LINEAR);
        t.addTimeKeyframe(0,1000);t.addTimeKeyframe(10000,1000);
        t.addPositionKeyframe(0,0,2,0,170,0,0,-1);
        t.addPositionKeyframe(10000,10,2,0,-170,0,0,-1);
        t.setInterpolator(0,InterpolatorType.LINEAR.newInstance());return t;
    }
    @Test void validatesExactlyTheRendererNativeLinearPathAndWrappedRotation() {
        var t=linear();assertNull(NativeClearancePolicy.unsupportedReason(t,0,10000));
        var p=t.getPositionPath().getValue(CameraProperties.POSITION,5000).orElseThrow();
        assertEquals(5,p.getLeft(),1e-9);assertEquals(2,p.getMiddle(),1e-9);
        var r=t.getPositionPath().getValue(CameraProperties.ROTATION,5000).orElseThrow();
        assertEquals(180,Math.abs(r.getLeft()),1e-6);
        assertTrue(SweptClearance.intersects(new double[]{0,2,0},new double[]{10,2,0},new double[]{4.99,1,-1},new double[]{5.01,3,1},.5));
    }
    @Test void rejectsCurvesAndPotentialOvershootInsteadOfCheckingTheirChord() {
        for(var type: new InterpolatorType[]{InterpolatorType.CUBIC,InterpolatorType.CATMULL_ROM}) {
            var t=linear();t.setInterpolator(0,type.newInstance());
            assertNotNull(NativeClearancePolicy.unsupportedReason(t,0,10000));
        }
    }
    @Test void rejectsUncoveredAndTemporalGeometryEvenWhenEndpointTimesMatch() {
        var t=linear();assertNotNull(NativeClearancePolicy.unsupportedReason(t,0,11000));
        t.addTimeKeyframe(5000,2000);
        assertTrue(NativeClearancePolicy.unsupportedReason(t,0,10000).contains("changing"));
        assertNotNull(NativeClearancePolicy.unsupportedReason(t,0,5000));
    }
    @Test void allowsOnlyExactOneXWhenPacketHistoryIsSeparatelyEstablished() {
        var t=new SPTimeline();t.setDefaultInterpolatorType(InterpolatorType.LINEAR);
        t.addTimeKeyframe(0,1000);t.addTimeKeyframe(10000,11000);
        t.addPositionKeyframe(0,0,2,0,0,0,0,-1);t.addPositionKeyframe(10000,10,2,0,0,0,0,-1);
        t.setInterpolator(0,InterpolatorType.LINEAR.newInstance());
        assertNull(NativeClearancePolicy.unsupportedReason(t,0,10000,true));
        assertNotNull(NativeClearancePolicy.unsupportedReason(t,0,10000));
        t.addTimeKeyframe(5000,4000);assertNotNull(NativeClearancePolicy.unsupportedReason(t,0,10000,true));
    }
}
