package com.google.vr.vrcore;
import android.content.pm.PackageManager;
public class Checks extends android.app.Activity {
    public static boolean daydreamReady(PackageManager pm) {
        return pm.hasSystemFeature("android.hardware.vr.high_performance") && pm.hasSystemFeature("android.software.vr.mode");
    }
    public static boolean camera(PackageManager pm) { return pm.hasSystemFeature("android.hardware.camera"); }
    public static boolean ranged(PackageManager pm, long a, long b, long c) {
        // many registers so the call may use invoke-virtual/range
        long x = a * b + c; long y = x * a - b; long z = y + c * x;
        boolean r = pm.hasSystemFeature("android.hardware.vr.high_performance", (int) (x + y + z));
        return r;
    }
    public void enter() {
        try { setVrModeEnabled(true, new android.content.ComponentName("a", "b")); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static boolean trusted(com.google.android.gms.common.GoogleSignatureVerifier v) { return v.isPackageGoogleSigned("com.google.vr.vrcore"); }
}
