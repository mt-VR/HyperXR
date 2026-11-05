package com.hypermagic.hyperdreamed;

import android.os.ParcelFileDescriptor;

// Runs in a Shizuku UserService process with the shell UID (which has READ_LOGS and can install).
interface ILogService {
    // Shizuku calls this transaction code to stop the service.
    void destroy() = 16777114;

    // uid and Android version as seen by the service.
    String info() = 1;

    // `logcat -c`: clears the log so the next capture starts fresh.
    void clearLog() = 2;

    // The crash buffer plus the main/system lines that mention any of [packagesCsv] or VR tags.
    String dump(String packagesCsv, int maxLines) = 3;

    // `pm install -r` of the APK read from [apk] ([size] bytes); "" or the error text.
    String installApk(in ParcelFileDescriptor apk, long size) = 4;

    // `pm uninstall <pkg>`; "" or the error text.
    String uninstall(String pkg) = 5;

    // Starts [pkg]'s launcher activity through `monkey`; "" or the error text.
    String launch(String pkg) = 6;
}
