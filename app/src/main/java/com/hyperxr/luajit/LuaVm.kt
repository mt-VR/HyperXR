package com.hyperxr.luajit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Host side of a Lua call: implemented by the game runtime. Arguments arrive in [LuaVm.args]
 * (tag + double per slot) and [LuaVm.strArgs]; results are written back the same way.
 */
interface LuaHost {
    /** Called before the VM boots: the bootstrap already performs host calls while being created. */
    fun onAttach(vm: LuaVm) {}
    fun hostCall(name: String, argc: Int): Int
    fun onLog(level: Int, message: String)
}

/**
 * One LuaJIT state running one package. Buffers are direct and shared with native code:
 *  - input  (256 floats)  Kotlin -> Lua: head, hands, controllers, gaze/tap
 *  - header (32 floats)   Lua -> Kotlin: rig pose, lighting, background, counters, requests
 *  - draws  (18 floats per draw), ui (12 per command), panels (14 per panel)
 * Layouts are documented in docs/SDK.md and mirrored in bootstrap.lua.
 */
class LuaVm private constructor(val host: LuaHost) {
    val input: FloatBuffer = direct(INPUT_FLOATS * 4).asFloatBuffer()
    val header: FloatBuffer = direct(HEADER_FLOATS * 4).asFloatBuffer()
    val draws: FloatBuffer = direct(MAX_DRAWS * DRAW_STRIDE * 4).asFloatBuffer()
    val ui: FloatBuffer = direct(MAX_UI * UI_STRIDE * 4).asFloatBuffer()
    val panels: FloatBuffer = direct(MAX_PANELS * PANEL_STRIDE * 4).asFloatBuffer()
    val args: ByteBuffer = direct(MAX_ARGS * ARG_STRIDE)
    val strArgs = arrayOfNulls<String>(MAX_ARGS)
    val strRets = arrayOfNulls<String>(MAX_ARGS)
    private var handle = 0L
    private var inputBytes: ByteBuffer? = null

    val alive: Boolean get() = handle != 0L

    // ---------------------------------------------------------------- argument helpers
    fun argTag(i: Int): Int = args.getInt(i * ARG_STRIDE)
    fun argNum(i: Int): Double = if (argTag(i) == TAG_NUMBER || argTag(i) == TAG_BOOL) args.getDouble(i * ARG_STRIDE + 8) else 0.0
    fun argFloat(i: Int): Float = argNum(i).toFloat()
    fun argInt(i: Int): Int = argNum(i).toInt()
    fun argStr(i: Int): String? = if (argTag(i) == TAG_STRING) strArgs[args.getDouble(i * ARG_STRIDE + 8).toInt()] else null

    private var retCount = 0
    private var retStr = 0
    fun beginReturn() { retCount = 0; retStr = 0 }
    fun retNum(v: Double) { put(TAG_NUMBER, v) }
    fun retBool(v: Boolean) { put(TAG_BOOL, if (v) 1.0 else 0.0) }
    fun retNil() { put(TAG_NIL, 0.0) }
    fun retStr(v: String?) {
        if (v == null) { retNil(); return }
        strRets[retStr] = v
        put(TAG_STRING, retStr.toDouble())
        retStr++
    }
    fun returned(): Int = retCount
    private fun put(tag: Int, v: Double) {
        if (retCount >= MAX_ARGS) return
        args.putInt(retCount * ARG_STRIDE, tag)
        args.putDouble(retCount * ARG_STRIDE + 8, v)
        retCount++
    }

    // ---------------------------------------------------------------- lifecycle
    fun frame(dt: Float): Int = if (handle == 0L) -1 else nativeFrame(handle, dt)
    fun event(kind: String, id: Int, status: Int, data: String) { if (handle != 0L) nativeEvent(handle, kind, id, status, data) }
    fun crashReason(): String? = if (handle == 0L) "not running" else nativeCrashReason(handle)
    fun destroy() { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }

    data class Config(
        val id: String, val name: String, val version: String, val mode: String, val entry: String,
        val permissions: List<String>, val strict: Boolean, val budget: Int = 20_000_000, val memoryMb: Int = 192,
        val windowW: Float = 0.9f, val windowH: Float = 0.6f,
    )

    companion object {
        const val INPUT_FLOATS = 256
        const val HEADER_FLOATS = 32
        const val DRAW_STRIDE = 18
        const val UI_STRIDE = 12
        const val PANEL_STRIDE = 14
        const val MAX_DRAWS = 4096
        const val MAX_UI = 8192
        const val MAX_PANELS = 64
        const val MAX_ARGS = 16
        const val ARG_STRIDE = 16
        const val TAG_NIL = 0
        const val TAG_NUMBER = 1
        const val TAG_BOOL = 2
        const val TAG_STRING = 3

        // input layout
        const val IN_TIME = 0; const val IN_DT = 1; const val IN_MR = 2; const val IN_HANDS_AVAILABLE = 3
        const val IN_HEAD_POS = 4; const val IN_HEAD_ROT = 7
        const val IN_HAND_LEFT = 16; const val IN_HAND_RIGHT = 96
        const val IN_CTRL_LEFT = 176; const val IN_CTRL_RIGHT = 208
        const val IN_GAZE = 240

        // header layout
        const val H_RECENTER = 21; const val H_EXIT = 22
        const val H_DRAWS = 24; const val H_UI = 25; const val H_PANELS = 26; const val H_OVERFLOW = 27; const val H_MEMKB = 28

        init { System.loadLibrary("hyperxr") }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        fun create(host: LuaHost, sdkSources: Map<String, String>, c: Config): LuaVm {
            val vm = LuaVm(host)
            host.onAttach(vm)
            val names = sdkSources.keys.toTypedArray()
            val sources = names.map { sdkSources.getValue(it) }.toTypedArray()
            vm.handle = nativeCreate(
                host, names, sources, c.id, c.name, c.version, c.mode, c.entry, c.permissions.toTypedArray(),
                c.strict, c.budget, c.memoryMb, c.windowW, c.windowH,
                bufferOf(vm.input), bufferOf(vm.header), bufferOf(vm.draws), bufferOf(vm.ui), bufferOf(vm.panels), vm.args,
                vm.strArgs, vm.strRets,
            )
            return vm
        }

        /** FloatBuffer views created by asFloatBuffer() are direct; JNI resolves their address. */
        private fun bufferOf(fb: FloatBuffer): java.nio.Buffer = fb

        fun version(): String = nativeVersion()

        @JvmStatic private external fun nativeCreate(
            host: Any, sdkNames: Array<String>, sdkSources: Array<String>,
            id: String, name: String, version: String, mode: String, entry: String, perms: Array<String>,
            strict: Boolean, budget: Int, memoryMb: Int, windowW: Float, windowH: Float,
            input: java.nio.Buffer, header: java.nio.Buffer, draws: java.nio.Buffer, ui: java.nio.Buffer, panels: java.nio.Buffer,
            args: java.nio.Buffer, strArgs: Array<String?>, strRets: Array<String?>,
        ): Long
        @JvmStatic private external fun nativeFrame(handle: Long, dt: Float): Int
        @JvmStatic private external fun nativeEvent(handle: Long, kind: String, id: Int, status: Int, data: String)
        @JvmStatic private external fun nativeCrashReason(handle: Long): String?
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeVersion(): String
    }
}
