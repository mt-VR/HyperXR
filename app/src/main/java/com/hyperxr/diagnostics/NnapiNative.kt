package com.hyperxr.diagnostics

object NnapiNative {
    init { System.loadLibrary("hyperxr") }

    /** Rows "name|type|featureLevel" for every NNAPI device the driver reports (empty below Android 10). */
    fun devices(): List<String> = nativeDevices().toList()

    @JvmStatic private external fun nativeDevices(): Array<String>
}
