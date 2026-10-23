package com.hyperxr.vrcore;

/** Runs as the shell user through Shizuku: lists and opens /dev/input devices for HyperXR Daydream. */
interface IInputOpener {
    /** Output of `getevent -p`. */
    String listDevices() = 1;
    /** Read-only descriptor of an input device, or null. */
    ParcelFileDescriptor open(String path) = 2;
    /** Shizuku's reserved transaction for stopping a user service. */
    void destroy() = 16777114;
}
