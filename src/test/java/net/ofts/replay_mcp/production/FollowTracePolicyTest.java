package net.ofts.replay_mcp.production;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FollowTracePolicyTest {
 @Test void requiresNativeTickCadenceAndRejectsDiscontinuousEyes() {
  assertTrue(FollowTracePolicy.supportedFps(60));assertFalse(FollowTracePolicy.supportedFps(30));
  assertTrue(FollowTracePolicy.same(new double[]{1,2,3},new double[]{1,2,3}));
  assertFalse(FollowTracePolicy.same(new double[]{1,2,3},new double[]{1,2.01,3}));
 }
 @Test void testsFullRelativeDistanceNotOnlyEndpoints() {
  double[] eye={0,2,0};assertFalse(FollowTracePolicy.distanceBound(new double[]{-4,3,0},new double[]{4,3,0},eye,eye,3,5));
  assertTrue(FollowTracePolicy.distanceBound(new double[]{-4,3,0},new double[]{-4,3,2},eye,new double[]{0,2,2},3,5));
 }
 @Test void visibilityIsConservativeForMovingCameraAndSubject() {
  assertTrue(FollowTracePolicy.sightHullIntersects(new double[]{-4,3,0},new double[]{-4,3,1},new double[]{0,2,0},new double[]{0,2,1},new double[]{-2,2,-1},new double[]{-1.9,4,2}));
  assertFalse(FollowTracePolicy.sightHullIntersects(new double[]{-4,3,0},new double[]{-4,3,1},new double[]{0,2,0},new double[]{0,2,1},new double[]{-2,-1,-1},new double[]{-1.9,1,2}));
 }
}
