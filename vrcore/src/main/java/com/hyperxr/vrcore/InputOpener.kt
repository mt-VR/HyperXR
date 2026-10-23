package com.hyperxr.vrcore

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku user service, running as the shell user (who may read /dev/input): it only lists the
 * input devices and hands back read-only descriptors; all parsing happens in the app.
 */
class InputOpener : IInputOpener.Stub() {
    override fun listDevices(): String = try {
        val p = ProcessBuilder("getevent", "-p").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (t: Throwable) { "" }

    override fun open(path: String): ParcelFileDescriptor? {
        if (!path.startsWith("/dev/input/event") || path.contains("..")) return null
        return try { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) } catch (t: Throwable) { null }
    }

    override fun destroy() { exitProcess(0) }
}
