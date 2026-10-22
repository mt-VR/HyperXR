package com.hyperxr.broker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.util.List;

/**
 * Answers the OpenXR loader's "which runtime is active?" query with the HyperXR runtime.
 *
 * The Khronos Android loader asks the installable broker (org.khronos.openxr.runtime_broker) first
 * and the system broker second. This app takes the installable broker's authority: some phones
 * already declare the system one (installing a second copy fails), and the first one asked wins. Contract (BrokerContract in OpenXR-SDK-Source android_utilities.cpp):
 *  - content://authority/openxr/major/abi/abi/runtimes/active/0
 *      -> _id, package_name, native_lib_dir, so_filename, has_functions
 *  - content://authority/openxr/major/abi/abi/runtimes/package/functions/0
 *      -> _id, function_name, symbol_name (empty: the library exports the standard symbols)
 */
public final class SystemBrokerProvider extends ContentProvider {
    private static final String RUNTIME_PACKAGE = "com.hyperxr";
    private static final String SO_NAME = "libhyperxr_openxr.so";

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 6 || !seg.get(0).equals("openxr") || !seg.get(2).equals("abi") || !seg.get(4).equals("runtimes")) return null;
        if (seg.get(5).equals("active")) {
            MatrixCursor c = new MatrixCursor(new String[] {"_id", "package_name", "native_lib_dir", "so_filename", "has_functions"});
            String libDir = runtimeLibDir();
            // only OpenXR 1.x, and only the ABI the runtime was installed for (a 32-bit game
            // cannot load a 64-bit library)
            if (libDir != null && seg.get(1).equals("1") && sameAbi(seg.get(3), abiOf(libDir)) && new File(libDir, SO_NAME).isFile()) {
                c.addRow(new Object[] {0, RUNTIME_PACKAGE, libDir, SO_NAME, 0});
            }
            return c;
        }
        if (seg.size() >= 7 && seg.get(6).equals("functions")) return new MatrixCursor(new String[] {"_id", "function_name", "symbol_name"});
        return null;
    }

    private String runtimeLibDir() {
        try {
            ApplicationInfo ai = getContext().getPackageManager().getApplicationInfo(RUNTIME_PACKAGE, 0);
            return ai.nativeLibraryDir;
        } catch (Exception e) {
            return null; // HyperXR not installed
        }
    }

    /** The Khronos loader names 32-bit ARM "armeabi-v7a" or "armeabi-v7l" depending on its version. */
    private static boolean sameAbi(String asked, String installed) {
        if (asked.startsWith("armeabi") && installed.startsWith("armeabi")) return true;
        return asked.equals(installed);
    }

    /** The package manager installs native libraries to .../lib/(arm64|arm|x86_64|x86). */
    private static String abiOf(String libDir) {
        String last = libDir.substring(libDir.lastIndexOf('/') + 1);
        switch (last) {
            case "arm64": return "arm64-v8a";
            case "arm": return "armeabi-v7a";
            case "x86_64": return "x86_64";
            case "x86": return "x86";
            default: return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
