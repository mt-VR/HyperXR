package com.hyperxr.luajit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real JNI bridge (app/src/main/cpp/lua_bridge.cpp built for the host by
 * tools/build_host_bridge.sh) with the real LuaVm and SDK bootstrap. Skipped unless the host
 * library directory is passed with -Dhyperxr.hostBridge=<dir> (see docs/TESTING.md).
 */
class LuaBridgeHostTest {
    private lateinit var pkg: File
    private val sdkDir = File("src/main/assets/hxsdk")

    @Before
    fun setUp() {
        val dir = System.getProperty("hyperxr.hostBridge")
        assumeTrue("host bridge not built", dir != null && File(dir, "libhyperxr.so").isFile)
        pkg = Files.createTempDirectory("hxpkg").toFile()
    }

    private class Host(val pkg: File) : LuaHost {
        lateinit var vm: LuaVm
        val logs = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val texts = mutableListOf<String>()
        var lastError: String? = null
        var captureArgs = true
        val captured = HashMap<String, List<Any?>>()

        override fun hostCall(name: String, argc: Int): Int {
            calls += name
            if (captureArgs) captured[name] = (0 until argc).map { i ->
                when (vm.argTag(i)) { LuaVm.TAG_STRING -> vm.argStr(i); LuaVm.TAG_NUMBER -> vm.argNum(i); LuaVm.TAG_BOOL -> vm.argNum(i) != 0.0; else -> null }
            }
            vm.beginReturn()
            when (name) {
                "asset_text" -> {
                    val f = File(pkg, vm.argStr(0)!!)
                    if (f.isFile) vm.retStr(f.readText()) else { vm.retNil(); vm.retStr("not found") }
                }
                "text" -> {
                    texts += vm.argStr(0)!!
                    vm.retNum(texts.size.toDouble()); vm.retNum(100.0); vm.retNum(40.0)
                }
                "game_error" -> { lastError = vm.argStr(0); vm.retBool(true) }
                "echo" -> for (i in 0 until argc) when (vm.argTag(i)) {
                    LuaVm.TAG_STRING -> vm.retStr(vm.argStr(i))
                    LuaVm.TAG_BOOL -> vm.retBool(vm.argNum(i) != 0.0)
                    LuaVm.TAG_NUMBER -> vm.retNum(vm.argNum(i) * 2)
                    else -> vm.retNil()
                }
                else -> vm.retBool(true)
            }
            return vm.returned()
        }

        override fun onAttach(vm: LuaVm) { this.vm = vm }
        override fun onLog(level: Int, message: String) { logs += message }
    }

    private fun boot(main: String, perms: List<String>, mode: String = "scene"): Pair<LuaVm, Host> {
        File(pkg, "main.lua").writeText(main)
        val host = Host(pkg)
        val sdk = sdkDir.listFiles()!!.filter { it.extension == "lua" }.associate { it.nameWithoutExtension to it.readText() }
        val vm = LuaVm.create(host, sdk, LuaVm.Config("com.test.bridge", "Bridge", "1.0.0", mode, "main.lua", perms, strict = false))
        assertTrue("VM failed to start", vm.alive)
        return vm to host
    }

    @Test fun hostCallsMarshalNumbersStringsAndUnicode() {
        val (vm, host) = boot("""
            sound = hx.audio.synth{ wave = "sáw✓", freq = 220, freqEnd = 110, duration = 0.5 }
            hx.log("id=" .. tostring(sound))
        """.trimIndent(), listOf("audio"))
        host.captureArgs = true
        vm.frame(1 / 60f)
        val args = host.captured.getValue("audio_synth")
        assertEquals("sáw✓", args[0])
        assertEquals(220.0, args[1])
        assertEquals(110.0, args[2])
        assertEquals(0.5, args[3])
        assertTrue(host.logs.contains("id=true"))
        vm.destroy()
    }

    @Test fun sandboxCannotReachTheNativeTable() {
        val (vm, host) = boot("__hx_probe = (__hx == nil and N == nil and native == nil); hx.log(tostring(__hx_probe))", listOf("ui"))
        vm.frame(1 / 60f)
        assertTrue(host.logs.contains("true"))
        assertNull(vm.crashReason())
        vm.destroy()
    }

    @Test fun framesProduceDrawsPanelsAndLogs() {
        val (vm, host) = boot("""
            hx.log("olá do Lua")
            local p = hx.ui.panel{ width = 0.5, height = 0.3 }
            p:add(hx.ui.label{ text = "Calendário" })
            function onUpdate(dt)
              hx.scene.spawn{ position = hx.V3(0, 1, -2) }
            end
        """.trimIndent(), listOf("ui"))
        val draws1 = vm.frame(1 / 60f)
        assertEquals(1, draws1)
        assertEquals(1f, vm.header.get(LuaVm.H_PANELS))
        assertTrue(vm.header.get(LuaVm.H_UI) > 0)
        val draws2 = vm.frame(1 / 60f)
        assertEquals(2, draws2)
        assertEquals(2f, vm.header.get(LuaVm.H_DRAWS))
        // draw record: mesh id 1 (cube), position y = 1, z = -2
        assertEquals(1f, vm.draws.get(0))
        assertEquals(1f, vm.draws.get(8))
        assertEquals(-2f, vm.draws.get(9))
        assertTrue(host.logs.contains("olá do Lua"))
        assertTrue(host.texts.contains("Calendário"))
        assertNull(vm.crashReason())
        vm.destroy()
    }

    @Test fun inputBufferReachesLua() {
        val (vm, host) = boot("""
            function onUpdate(dt)
              local p = hx.spatial.head()
              hx.log(string.format("%.2f", p.y))
            end
        """.trimIndent(), listOf("spatial"))
        vm.input.put(LuaVm.IN_HEAD_POS + 1, 1.25f)
        vm.input.put(LuaVm.IN_HEAD_ROT + 3, 1f)
        vm.frame(1 / 60f)
        assertTrue(host.logs.toString(), host.logs.contains("1.25"))
        vm.destroy()
    }

    @Test fun runtimeErrorsAreReportedNotThrown() {
        val (vm, host) = boot("function onUpdate() error('boom') end", listOf("ui"))
        vm.frame(1 / 60f)
        assertTrue(vm.crashReason()!!.contains("boom"))
        assertTrue(host.calls.contains("game_error"))
        vm.destroy()
    }
}
