package com.oculus.systemdriver;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;

/**
 * The entry point the VrApi loader inside every Gear VR / Quest game looks for: it opens this
 * package, calls load64 (load32 in 32-bit games; the Ext versions carry one more argument) and gets
 * the address of a function that hands out the driver's VrApi functions by name. The driver here is
 * Compatibility-Layer's own VrApi, which draws through Compatibility-Layer Runtime (Compatibility-Layer).
 */
public final class DriverLoader {
    private static boolean loaded;

    private static native long procAddress();

    public static long load64(Context app, Context driver, int a, int b, int c, int d, int e) {
        return load(driver);
    }

    public static long load64Ext(Context app, Context driver, int a, int b, int c, int d, int e, long f) {
        return load(driver);
    }

    public static long load32(Context app, Context driver, int a, int b, int c, int d, int e) {
        return load(driver);
    }

    public static long load32Ext(Context app, Context driver, int a, int b, int c, int d, int e, long f) {
        return load(driver);
    }

    private static synchronized long load(Context driver) {
        if (!loaded) {
            String folder = libraryDir(driver);
            // The Compatibility-Layer loader first, so the driver finds it by name in this class loader's namespace.
            System.load(folder + "/libcompatibility_layer_loader.so");
            System.load(folder + "/libcompatibility_layer_vrapi.so");
            loaded = true;
        }
        return procAddress();
    }

    /** The driver's libraries for this process: 64-bit games get lib/arm64, 32-bit ones lib/arm. */
    private static String libraryDir(Context driver) {
        ApplicationInfo info = driver.getApplicationInfo();
        File primary = new File(info.nativeLibraryDir);
        File own = new File(primary.getParentFile(), android.os.Process.is64Bit() ? "arm64" : "arm");
        return new File(own, "libcompatibility_layer_vrapi.so").exists() ? own.getPath() : primary.getPath();
    }

    private DriverLoader() {
    }
}
