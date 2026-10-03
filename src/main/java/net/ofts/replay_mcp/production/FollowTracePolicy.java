package net.ofts.replay_mcp.production;

/** Subject interpolation is bounded at the native 50 ms tick cadence. */
public final class FollowTracePolicy {
    private FollowTracePolicy() { }
    public static boolean supportedFps(int fps) { return fps>=20 && fps<=120 && fps%20==0; }
    public static boolean same(double[] a,double[] b) { for(int i=0;i<3;i++) if(!Double.isFinite(a[i]) || !Double.isFinite(b[i]) || Math.abs(a[i]-b[i])>1e-7)return false;return true; }
    public static boolean distanceBound(double[] a,double[] b,double[] sa,double[] sb,double min,double max) {
        double x=a[0]-sa[0],z=a[2]-sa[2],dx=b[0]-sb[0]-x,dz=b[2]-sb[2]-z;
        double squared=dx*dx+dz*dz,t=squared==0?0:Math.max(0,Math.min(1,-(x*dx+z*dz)/squared));
        return Math.hypot(x+dx*t,z+dz*t)>=min-1e-7 && Math.max(Math.hypot(x,z),Math.hypot(x+dx,z+dz))<=max+1e-7;
    }
    public static boolean sightHullIntersects(double[] a,double[] b,double[] sa,double[] sb,double[] min,double[] max) {
        for(int i=0;i<3;i++) if(Math.max(Math.max(a[i],b[i]),Math.max(sa[i],sb[i]))<min[i] || Math.min(Math.min(a[i],b[i]),Math.min(sa[i],sb[i]))>max[i])return false;
        return true;
    }
}
