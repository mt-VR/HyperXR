package com.hyperxr.shell;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

// Runs in a Shizuku UserService process with the shell UID.
interface IShellService {
    // Shizuku calls this transaction code to stop the service.
    void destroy() = 16777114;

    // Creates a virtual display rendering into [surface]; returns its display id or -1.
    int createDisplay(in Surface surface, int width, int height, int dpi, String name) = 1;

    // Starts [component] (package/activity) on the display; returns "" or the error text.
    String startApp(String component, int displayId) = 2;

    boolean injectMotion(int displayId, int action, float x, float y, long downTime, long eventTime) = 3;

    boolean injectKey(int displayId, int keyCode) = 4;

    String injectText(int displayId, String text) = 5;

    void releaseDisplay(int displayId) = 6;

    // Diagnostic: uid and Android version as seen by the service.
    String info() = 7;

    // Opens an http(s) URL with ACTION_VIEW on the display; returns "" or the error text.
    String openUrl(int displayId, String url) = 8;

    // Why the last call failed (exception text), "" if it did not.
    String lastError() = 9;

    // Installs the APK read from [apk] ([size] bytes) with `pm install -r`; "" or the error text.
    String installApk(in ParcelFileDescriptor apk, long size) = 10;

    // Copies [obb] to /sdcard/Android/obb/<pkg>/<name> (shell may write there); "" or the error.
    String copyObb(in ParcelFileDescriptor obb, String pkg, String name) = 11;

    // `pm uninstall <pkg>` (an original signed with another key blocks installing the patched one).
    String uninstall(String pkg) = 12;

    // The recent system log lines of [pkg]'s processes (logcat as shell), newline-separated.
    String gameLog(String pkg) = 13;
}
