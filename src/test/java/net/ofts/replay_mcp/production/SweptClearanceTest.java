package net.ofts.replay_mcp.production;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SweptClearanceTest {
 @Test void catchesWallBetweenClearEndpointsIncludingThinShapesAndMargin() {
  assertTrue(SweptClearance.intersects(new double[]{0,1,0},new double[]{10,1,0},new double[]{4.99,0,-1},new double[]{5.01,2,1},.5));
  assertTrue(SweptClearance.intersects(new double[]{0,1,0},new double[]{10,1,0},new double[]{5,1.4,-1},new double[]{6,2,1},.5));
  assertFalse(SweptClearance.intersects(new double[]{0,1,0},new double[]{10,1,0},new double[]{5,1.6,-1},new double[]{6,2,1},.5));
 }
 @Test void checksStationaryClearanceAndSlabs() {
  assertTrue(SweptClearance.intersects(new double[]{0,.8,0},new double[]{0,.8,0},new double[]{-1,0,-1},new double[]{1,.5,1},.5));
  assertFalse(SweptClearance.intersects(new double[]{0,2.1,0},new double[]{10,2.1,0},new double[]{-1,0,-1},new double[]{11,.5,1},.5));
 }
 @Test void boundsContactsForStepsCeilingsAndOverhangingFenceShapes() {
  double[] a={0,2.1,0}, b={10,2.1,0};
  assertFalse(SweptClearance.intersects(a,b,new double[]{4,0,-1},new double[]{5,.5,1},.5));
  assertFalse(SweptClearance.intersects(a,b,new double[]{4,.5,-1},new double[]{4.5,1,1},.5));
  assertTrue(SweptClearance.intersects(a,b,new double[]{4,2.5,-1},new double[]{5,3,1},.5));
  assertTrue(SweptClearance.intersects(new double[]{0,1.8,0},new double[]{10,1.8,0},new double[]{5,0,-.1},new double[]{5.1,1.5,.1},.5));
  assertEquals(.45,SweptClearance.firstIntersection(new double[]{0,1,0},new double[]{10,1,0},new double[]{5,0,-1},new double[]{6,2,1},.5),1e-9);
 }
}
