package com.hyperxr.openxr;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Marker service through which the Khronos OpenXR runtime broker discovers the HyperXR runtime
 * (the manifest metadata names libhyperxr_openxr.so). The runtime itself is loaded by the OpenXR
 * loader into the application's process; nothing binds to this service.
 */
public final class RuntimeService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
}
