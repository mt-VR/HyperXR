package com.hyperxr.daydream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Linux input devices (/dev/input/event*), read through Shizuku (the shell user may open them):
 * Joy-Cons and gamepads with their sticks, and - when the kernel driver exposes it (hid-nintendo,
 * hid-playstation) - the controller's own IMU as a separate "motion" device.
 */
object Evdev {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_ABS = 3
    const val SYN_REPORT = 0

    const val ABS_X = 0x00; const val ABS_Y = 0x01; const val ABS_Z = 0x02
    const val ABS_RX = 0x03; const val ABS_RY = 0x04; const val ABS_RZ = 0x05
    const val ABS_HAT0X = 0x10; const val ABS_HAT0Y = 0x11

    const val BTN_SOUTH = 0x130; const val BTN_EAST = 0x131; const val BTN_NORTH = 0x133; const val BTN_WEST = 0x134
    const val BTN_Z = 0x135
    const val BTN_TL = 0x136; const val BTN_TR = 0x137; const val BTN_TL2 = 0x138; const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a; const val BTN_START = 0x13b; const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d; const val BTN_THUMBR = 0x13e
    const val INPUT_PROP_ACCELEROMETER = 0x06

    data class Event(val type: Int, val code: Int, val value: Int)

    /** Size of struct input_event for the reading process: timeval is two longs. */
    fun eventSize(is64Bit: Boolean) = if (is64Bit) 24 else 16

    /** Decodes every whole input_event in [buf] (little-endian, as on ARM). */
    fun parse(buf: ByteArray, len: Int, is64Bit: Boolean): List<Event> {
        val size = eventSize(is64Bit)
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Event>(len / size)
        var p = 0
        while (p + size <= len) {
            val o = p + size - 8
            out += Event(bb.getShort(o).toInt() and 0xffff, bb.getShort(o + 2).toInt() and 0xffff, bb.getInt(o + 4))
            p += size
        }
        return out
    }

    class AbsInfo(val min: Int, val max: Int, val flat: Int, val resolution: Int)

    class Device(val path: String, var name: String = "") {
        val keys = HashSet<Int>()
        val abs = HashMap<Int, AbsInfo>()
        val props = HashSet<Int>()
        val isMotion get() = INPUT_PROP_ACCELEROMETER in props || name.contains("IMU") || name.contains("Motion Sensors")
        val isGamepad get() = !isMotion && (BTN_SOUTH in keys || BTN_EAST in keys || BTN_MODE in keys) && (ABS_X in abs || ABS_RX in abs)
        override fun toString() = "$name ($path)"
    }

    private val propNames = mapOf("INPUT_PROP_ACCELEROMETER" to INPUT_PROP_ACCELEROMETER)

    /** Parses `getevent -p` (numeric codes) into devices. */
    fun parseGeteventP(text: String): List<Device> {
        val out = ArrayList<Device>()
        var dev: Device? = null
        var section = ""
        val absLine = Regex("""([0-9a-fA-F]{4})\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+),\s*fuzz\s+-?\d+,\s*flat\s+(-?\d+),\s*resolution\s+(-?\d+)""")
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.startsWith("add device") -> { dev = Device(t.substringAfter(": ").trim()).also { out += it }; section = "" }
                dev == null -> {}
                t.startsWith("name:") -> dev!!.name = t.substringAfter("\"").substringBeforeLast("\"")
                t.startsWith("input props:") -> section = "PROPS"
                Regex("""^[A-Z]{3}\s+\(([0-9a-fA-F]{4})\):""").containsMatchIn(t) -> {
                    section = t.substring(0, 3)
                    parseSection(dev!!, section, t.substringAfter("):"), absLine)
                }
                section == "PROPS" -> {
                    val p = propNames[t] ?: t.toIntOrNull(16)
                    if (p != null) dev!!.props += p
                }
                section.isNotEmpty() && raw.startsWith(" ") -> parseSection(dev!!, section, t, absLine)
            }
        }
        return out
    }

    private fun parseSection(d: Device, section: String, body: String, absLine: Regex) {
        when (section) {
            "KEY" -> Regex("""\b([0-9a-fA-F]{4})\b""").findAll(body).forEach { d.keys += it.groupValues[1].toInt(16) }
            "ABS" -> absLine.findAll(body).forEach { m ->
                d.abs[m.groupValues[1].toInt(16)] = AbsInfo(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
            }
        }
    }

    /** The gamepad to use and, if the kernel exposes it, its motion sensor (paired by name). */
    fun pick(devices: List<Device>): Pair<Device?, Device?> {
        val pads = devices.filter { it.isGamepad }
        // a right Joy-Con (or a pair) first: it is the one held like a Daydream controller
        val pad = pads.firstOrNull { it.name.contains("(R)") } ?: pads.firstOrNull { it.name.contains("Joy-Con") } ?: pads.firstOrNull()
        val motion = pad?.let { p -> devices.filter { it.isMotion }.firstOrNull { it.name.startsWith(p.name) || it.name.startsWith(p.name.substringBefore(" (")) } }
        return pad to motion
    }
}

/** State of one gamepad (+ motion device) folded from evdev events, read at each SYN_REPORT. */
class EvdevPad(private val pad: Evdev.Device, private val motion: Evdev.Device?) {
    private val keys = HashSet<Int>()
    private val absVal = HashMap<Int, Int>()
    private val imuVal = HashMap<Int, Int>()

    fun onPadEvent(e: Evdev.Event): Boolean = when (e.type) {
        Evdev.EV_KEY -> { if (e.value != 0) keys += e.code else keys -= e.code; false }
        Evdev.EV_ABS -> { absVal[e.code] = e.value; false }
        Evdev.EV_SYN -> e.code == Evdev.SYN_REPORT
        else -> false
    }

    fun onMotionEvent(e: Evdev.Event): Boolean = when (e.type) {
        Evdev.EV_ABS -> { imuVal[e.code] = e.value; false }
        Evdev.EV_SYN -> e.code == Evdev.SYN_REPORT
        else -> false
    }

    fun pressed(code: Int) = code in keys

    /** Stick axis in [-1, 1] (y up), with the device's flat zone removed. */
    fun axis(code: Int): Float {
        val info = pad.abs[code] ?: return 0f
        val v = absVal[code] ?: return 0f
        val c = (info.max + info.min) / 2f
        val half = ((info.max - info.min) / 2f).coerceAtLeast(1f)
        val flat = info.flat / half
        val x = ((v - c) / half).coerceIn(-1f, 1f)
        return if (kotlin.math.abs(x) <= flat) 0f else (x - kotlin.math.sign(x) * flat) / (1f - flat)
    }

    /** Largest of the two sticks, y up: a single Joy-Con reports its stick on either pair. */
    fun stick(): FloatArray {
        val l = floatArrayOf(axis(Evdev.ABS_X), -axis(Evdev.ABS_Y))
        val r = floatArrayOf(axis(Evdev.ABS_RX), -axis(Evdev.ABS_RY))
        return if (r[0] * r[0] + r[1] * r[1] > l[0] * l[0] + l[1] * l[1]) r else l
    }

    val hasImu get() = motion != null && motion.abs.containsKey(Evdev.ABS_RX)

    /** Gyro in rad/s (resolution = units per degree/s) in the device frame, or null. */
    fun gyro(): FloatArray? {
        val m = motion ?: return null
        val codes = intArrayOf(Evdev.ABS_RX, Evdev.ABS_RY, Evdev.ABS_RZ)
        return FloatArray(3) { i ->
            val res = m.abs[codes[i]]?.resolution?.takeIf { it > 0 } ?: return null
            Math.toRadians((imuVal[codes[i]] ?: 0).toDouble() / res).toFloat()
        }
    }

    /** Acceleration in m/s² (resolution = units per g) in the device frame, or null. */
    fun accel(): FloatArray? {
        val m = motion ?: return null
        val codes = intArrayOf(Evdev.ABS_X, Evdev.ABS_Y, Evdev.ABS_Z)
        return FloatArray(3) { i ->
            val res = m.abs[codes[i]]?.resolution?.takeIf { it > 0 } ?: return null
            ((imuVal[codes[i]] ?: 0).toDouble() / res * 9.80665).toFloat()
        }
    }

    /** Daydream buttons held, as a bit set of (1 shl DaydreamProtocol.BUTTON_*). */
    fun daydreamButtons(): Int {
        var b = 0
        fun on(id: Int) { b = b or (1 shl id) }
        // Nintendo "A" is BTN_EAST, an Xbox-style "A" is BTN_SOUTH: both click, like the touchpad press
        if (pressed(Evdev.BTN_EAST) || pressed(Evdev.BTN_SOUTH) || pressed(Evdev.BTN_THUMBR) || pressed(Evdev.BTN_THUMBL)) on(DaydreamProtocol.BUTTON_CLICK)
        if (pressed(Evdev.BTN_NORTH) || pressed(Evdev.BTN_WEST)) on(DaydreamProtocol.BUTTON_APP)
        if (pressed(Evdev.BTN_MODE)) on(DaydreamProtocol.BUTTON_HOME)
        if (pressed(Evdev.BTN_TR2) || pressed(Evdev.BTN_TL2)) on(DaydreamProtocol.BUTTON_TRIGGER)
        if (pressed(Evdev.BTN_TR) || pressed(Evdev.BTN_TL)) on(DaydreamProtocol.BUTTON_GRIP)
        if (pressed(Evdev.BTN_START)) on(DaydreamProtocol.BUTTON_VOLUME_UP)
        if (pressed(Evdev.BTN_SELECT)) on(DaydreamProtocol.BUTTON_VOLUME_DOWN)
        return b
    }
}
