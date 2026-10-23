package com.hyperxr.daydream

/**
 * Wire protocol of the Daydream controller service, as the GVR SDK (1.x) speaks it to
 * Google VR Services (package com.google.vr.vrcore). HyperXR's own VrCore implements the service
 * side, so GVR apps get a controller made from a Joy-Con / gamepad.
 *
 * Learned by reading the SDK's client classes (ControllerServiceBridge, IControllerService,
 * IControllerListener, ControllerEventPacket[2]); nothing of Google's code is copied here.
 */
object DaydreamProtocol {
    const val PACKAGE = "com.google.vr.vrcore"
    const val BIND_ACTION = "com.google.vr.vrcore.controller.BIND"
    const val SETTINGS_AUTHORITY = "com.google.vr.vrcore.settings"
    const val SETTINGS_ACTION = "android.content.action.VR_SETTINGS_PROVIDER"

    /** API level the service reports (the SDK 1.200 client is 25; >= 21 enables the service listener). */
    const val SERVICE_API_VERSION = 25

    const val SERVICE_DESCRIPTOR = "com.google.vr.vrcore.controller.api.IControllerService"
    const val LISTENER_DESCRIPTOR = "com.google.vr.vrcore.controller.api.IControllerListener"
    const val SERVICE_LISTENER_DESCRIPTOR = "com.google.vr.vrcore.controller.api.IControllerServiceListener"

    // IControllerService transactions (client -> service)
    const val TX_INITIALIZE = 1                // (int clientApi) -> int ControllerInitResults
    const val TX_REGISTER_LISTENER = 5         // (int controllerId, String key, IBinder listener) -> boolean
    const val TX_UNREGISTER_LISTENER = 6       // (String key) -> boolean
    const val TX_REGISTER_SERVICE_LISTENER = 8 // (IBinder) -> boolean
    const val TX_UNREGISTER_SERVICE_LISTENER = 9
    const val TX_NUMBER_OF_CONTROLLERS = 10    // () -> int
    const val TX_REQUEST = 11                  // (int controllerId, ControllerRequest?) -> void (vibration)

    // IControllerListener transactions (service -> client)
    const val LTX_API_VERSION = 1
    const val LTX_STATE_CHANGED = 2            // (int controllerId, int state)
    const val LTX_OPTIONS = 9                  // () -> ControllerListenerOptions?
    const val LTX_EVENT_PACKET = 10            // (ControllerEventPacket?)
    const val LTX_RECENTERED = 11              // (ControllerOrientationEvent?)
    const val LTX_EVENT_PACKET2 = 12           // (ControllerEventPacket2?)

    // IControllerServiceListener transactions
    const val SLTX_API_VERSION = 1
    const val SLTX_SERVICE_EVENT = 2

    const val INIT_SUCCESS = 0

    const val STATE_DISCONNECTED = 0
    const val STATE_SCANNING = 1
    const val STATE_CONNECTING = 2
    const val STATE_CONNECTED = 3

    const val BUTTON_CLICK = 1
    const val BUTTON_HOME = 2
    const val BUTTON_APP = 3
    const val BUTTON_VOLUME_UP = 5
    const val BUTTON_VOLUME_DOWN = 6
    const val BUTTON_TRIGGER = 7
    const val BUTTON_GRIP = 8

    // ControllerTouchEvent.action: 1 down and 2 move mean touching; 0, 3 (cancel), 4 (up) mean not
    const val TOUCH_DOWN = 1
    const val TOUCH_MOVE = 2
    const val TOUCH_UP = 4

    /** At most 16 events of each kind per packet (the client's arrays are 16 long and it checks). */
    const val MAX_EVENTS = 16
}

/** Where a packet is written: android.os.Parcel on the phone, a byte buffer in the tests. */
interface ParcelSink {
    fun writeInt(v: Int)
    fun writeLong(v: Long)
    fun writeFloat(v: Float)
}

/** One batch of controller events, all for one controller. */
class ControllerFrame(val timestampNs: Long) {
    class Button(val button: Int, val down: Boolean)
    class Touch(val action: Int, val x: Float, val y: Float)

    var orientation: FloatArray? = null   // x, y, z, w
    var gyro: FloatArray? = null          // rad/s, controller frame
    var accel: FloatArray? = null         // m/s², controller frame
    val buttons = ArrayList<Button>()
    var touch: Touch? = null

    val isEmpty get() = orientation == null && gyro == null && accel == null && buttons.isEmpty() && touch == null
}

object DaydreamPackets {
    private const val EVENT_HEADER = 12      // long timestampNanos + int controllerId

    private fun header(s: ParcelSink, f: ControllerFrame, controllerId: Int) { s.writeLong(f.timestampNs); s.writeInt(controllerId) }

    /** ControllerEventPacket: version, then (count, events) for accel, button, gyro, orientation, touch. */
    fun writePacket(s: ParcelSink, f: ControllerFrame, controllerId: Int = 0) {
        s.writeInt(1)
        val accel = f.accel
        s.writeInt(if (accel != null) 1 else 0)
        if (accel != null) { header(s, f, controllerId); accel.take(3).forEach(s::writeFloat) }
        val buttons = f.buttons.take(DaydreamProtocol.MAX_EVENTS)
        s.writeInt(buttons.size)
        for (b in buttons) { header(s, f, controllerId); s.writeInt(b.button); s.writeInt(if (b.down) 1 else 0) }
        val gyro = f.gyro
        s.writeInt(if (gyro != null) 1 else 0)
        if (gyro != null) { header(s, f, controllerId); gyro.take(3).forEach(s::writeFloat) }
        val q = f.orientation
        s.writeInt(if (q != null) 1 else 0)
        if (q != null) { header(s, f, controllerId); q.take(4).forEach(s::writeFloat) }
        val t = f.touch
        s.writeInt(if (t != null) 1 else 0)
        if (t != null) { header(s, f, controllerId); s.writeInt(0); s.writeInt(t.action); s.writeFloat(t.x); s.writeFloat(t.y) }
    }

    /** Bytes of [writePacket]'s output. */
    fun packetBytes(f: ControllerFrame): Int {
        var n = 4 + 5 * 4
        if (f.accel != null) n += EVENT_HEADER + 12
        n += f.buttons.take(DaydreamProtocol.MAX_EVENTS).size * (EVENT_HEADER + 8)
        if (f.gyro != null) n += EVENT_HEADER + 12
        if (f.orientation != null) n += EVENT_HEADER + 16
        if (f.touch != null) n += EVENT_HEADER + 16
        return n
    }

    /**
     * ControllerEventPacket2: its total length (counting the length int itself), the packet above,
     * then position events, battery, a millisecond timestamp and tracking-status events. The client
     * reads the tail only while inside the length, so the length must be exact.
     */
    fun writePacket2(s: ParcelSink, f: ControllerFrame, controllerId: Int = 0) {
        s.writeInt(packet2Bytes(f))
        writePacket(s, f, controllerId)
        s.writeInt(0)                         // position events
        s.writeInt(0)                         // no battery event
        s.writeLong(f.timestampNs / 1_000_000)
        s.writeInt(0)                         // tracking-status events
    }

    fun packet2Bytes(f: ControllerFrame): Int = 4 + packetBytes(f) + 4 + 4 + 8 + 4

    /** ControllerOrientationEvent, sent with onControllerRecentered. */
    fun writeOrientationEvent(s: ParcelSink, timestampNs: Long, q: FloatArray, controllerId: Int = 0) {
        s.writeLong(timestampNs); s.writeInt(controllerId); q.take(4).forEach(s::writeFloat)
    }
}
