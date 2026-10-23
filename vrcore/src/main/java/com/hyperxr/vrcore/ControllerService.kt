package com.hyperxr.vrcore

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.hyperxr.daydream.ControllerFrame
import com.hyperxr.daydream.DaydreamPackets
import com.hyperxr.daydream.DaydreamProtocol as P
import com.hyperxr.daydream.ParcelSink

/**
 * The Daydream controller service the GVR SDK binds ("com.google.vr.vrcore.controller.BIND"):
 * speaks IControllerService by hand (see DaydreamProtocol) and streams ControllerEventPacket2s
 * of the emulated controller to every registered app listener.
 */
class ControllerService : Service() {
    private class Listener(val key: String, val controllerId: Int, val binder: IBinder) {
        var packet2 = true          // falls back to ControllerEventPacket for very old clients
        var death: IBinder.DeathRecipient? = null
    }

    private val listeners = LinkedHashMap<String, Listener>()
    private var stopInput: (() -> Unit)? = null

    private class PSink(val p: Parcel) : ParcelSink {
        override fun writeInt(v: Int) = p.writeInt(v)
        override fun writeLong(v: Long) = p.writeLong(v)
        override fun writeFloat(v: Float) = p.writeFloat(v)
    }

    private val binder = object : Binder() {
        init { attachInterface(null, P.SERVICE_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                P.TX_INITIALIZE, P.TX_REGISTER_LISTENER, P.TX_UNREGISTER_LISTENER, P.TX_REGISTER_SERVICE_LISTENER,
                P.TX_UNREGISTER_SERVICE_LISTENER, P.TX_NUMBER_OF_CONTROLLERS, P.TX_REQUEST -> data.enforceInterface(P.SERVICE_DESCRIPTOR)
                else -> return super.onTransact(code, data, reply, flags)
            }
            fun ok(v: Int? = null) { reply?.writeNoException(); if (v != null) reply?.writeInt(v) }
            when (code) {
                P.TX_INITIALIZE -> { val api = data.readInt(); Log.i(TAG, "client API $api"); ok(P.INIT_SUCCESS) }
                P.TX_REGISTER_LISTENER -> {
                    val id = data.readInt(); val key = data.readString() ?: ""; val b = data.readStrongBinder()
                    ok(if (b != null) { register(Listener(key, id, b)); 1 } else 0)
                }
                P.TX_UNREGISTER_LISTENER -> { unregister(data.readString() ?: ""); ok(1) }
                P.TX_REGISTER_SERVICE_LISTENER, P.TX_UNREGISTER_SERVICE_LISTENER -> { data.readStrongBinder(); ok(1) }
                P.TX_NUMBER_OF_CONTROLLERS -> ok(1)
                P.TX_REQUEST -> ok()      // vibration: Joy-Con rumble is not reachable from here
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(listeners) { listeners.values.toList() }.forEach { unregister(it.key) }
        return false
    }

    private fun register(l: Listener) {
        unregister(l.key)
        val d = IBinder.DeathRecipient { unregister(l.key) }
        try { l.binder.linkToDeath(d, 0); l.death = d } catch (t: Throwable) { return }
        val first = synchronized(listeners) { listeners[l.key] = l; listeners.size == 1 }
        call(l.binder, P.LTX_STATE_CHANGED) { it.writeInt(l.controllerId); it.writeInt(P.STATE_CONNECTED) }
        if (first) stopInput = ControllerInput.use(this, ::broadcast)
        Log.i(TAG, "listener ${l.key} for controller ${l.controllerId}")
    }

    private fun unregister(key: String) {
        val (l, empty) = synchronized(listeners) { listeners.remove(key) to listeners.isEmpty() }
        l?.death?.let { runCatching { l.binder.unlinkToDeath(it, 0) } }
        if (l != null && empty) { stopInput?.invoke(); stopInput = null }
    }

    private fun broadcast(frame: ControllerFrame, recentered: Boolean) {
        for (l in synchronized(listeners) { listeners.values.toList() }) {
            if (l.packet2) {
                val handled = call(l.binder, P.LTX_EVENT_PACKET2) { it.writeInt(1); DaydreamPackets.writePacket2(PSink(it), frame, l.controllerId) }
                if (!handled) l.packet2 = false
            }
            if (!l.packet2) call(l.binder, P.LTX_EVENT_PACKET) { it.writeInt(1); DaydreamPackets.writePacket(PSink(it), frame, l.controllerId) }
            if (recentered) frame.orientation?.let { q ->
                call(l.binder, P.LTX_RECENTERED) { it.writeInt(1); DaydreamPackets.writeOrientationEvent(PSink(it), frame.timestampNs, q, l.controllerId) }
            }
        }
    }

    /** One synchronous call into an app's listener; false when the app does not know the call. */
    private fun call(b: IBinder, code: Int, write: (Parcel) -> Unit): Boolean {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(P.LISTENER_DESCRIPTOR)
            write(d)
            b.transact(code, d, r, 0).also { if (it) r.readException() }
        } catch (t: Throwable) {
            Log.w(TAG, "listener call $code: ${t.message}"); true
        } finally { d.recycle(); r.recycle() }
    }

    override fun onDestroy() { stopInput?.invoke(); stopInput = null; super.onDestroy() }

    companion object { private const val TAG = "HxVrCore" }
}
