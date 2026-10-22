package com.hyperxr.shell;

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
}
