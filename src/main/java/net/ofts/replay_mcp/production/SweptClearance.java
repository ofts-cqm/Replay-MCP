package net.ofts.replay_mcp.production;

/** Closed-segment versus expanded native collision box: conservative axis-aligned camera clearance. */
public final class SweptClearance {
    private SweptClearance() { }
    /** Minimize over all shapes in a segment; exact ties retain deterministic scan order. */
    public static final class FirstContact<T> {
        private double parameter = Double.POSITIVE_INFINITY;
        private T obstacle;
        public void consider(double candidate, T value) {
            if (Double.isFinite(candidate) && candidate >= 0 && candidate <= 1 && candidate < parameter) {
                parameter = candidate;
                obstacle = value;
            }
        }
        public boolean found() { return Double.isFinite(parameter); }
        public double parameter() { return parameter; }
        public T obstacle() { return obstacle; }
    }
    public static boolean intersects(double[] a, double[] b, double[] min, double[] max, double radius) {
        return Double.isFinite(firstIntersection(a,b,min,max,radius));
    }
    /** First conservative contact parameter, or NaN if disjoint. */
    public static double firstIntersection(double[] a, double[] b, double[] min, double[] max, double radius) {
        double enter = 0, leave = 1;
        for (int axis = 0; axis < 3; axis++) {
            double d = b[axis]-a[axis], low = min[axis]-radius, high = max[axis]+radius;
            if (Math.abs(d) < 1e-12) { if (a[axis] < low || a[axis] > high) return Double.NaN; }
            else { double t0 = (low-a[axis])/d, t1 = (high-a[axis])/d; enter=Math.max(enter,Math.min(t0,t1)); leave=Math.min(leave,Math.max(t0,t1)); if (enter>leave) return Double.NaN; }
        }
        return enter;
    }
}
