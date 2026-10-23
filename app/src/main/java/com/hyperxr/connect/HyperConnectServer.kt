package com.hyperxr.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.hyperxr.diagnostics.Diagnostics
import java.util.UUID

/**
 * HyperXR's side of HyperConnect: waits on Bluetooth (RFCOMM) for the controller phone, keeps
 * its newest state, and sends it vibrations. One controller at a time; after a disconnect it waits
 * for the next connection.
 */
class HyperConnectServer(private val context: Context) {
    @Volatile var state: HyperConnectProtocol.State? = null; private set
    @Volatile var receivedNs = 0L; private set
    @Volatile var deviceName = ""; private set
    @Volatile var leftHanded = false; private set
    @Volatile var status = "Desligado"; private set

    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private var thread: Thread? = null

    /** Connected and heard from in the last half second. */
    val connected get() = client != null && state != null && System.nanoTime() - receivedNs < 500_000_000L

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) { status = "Bluetooth desligado"; sleep(2000); continue }
                try {
                    status = "Esperando o celular controle"
                    val s = adapter.listenUsingInsecureRfcommWithServiceRecord(HyperConnectProtocol.SERVICE_NAME, UUID.fromString(HyperConnectProtocol.SERVICE_UUID))
                    server = s
                    val c = s.accept()
                    runCatching { s.close() }; server = null
                    client = c
                    deviceName = c.remoteDevice?.name ?: "celular"
                    status = "Conectado: $deviceName"
                    Diagnostics.log("HyperConnect: $deviceName conectado")
                    read(c)
                } catch (t: SecurityException) {
                    status = "Permita o Bluetooth para o HyperXR"; sleep(3000)
                } catch (t: Throwable) {
                    if (running) { status = "Esperando o celular controle"; sleep(500) }
                } finally {
                    client?.let { runCatching { it.close() }; Diagnostics.log("HyperConnect: $deviceName desconectado") }
                    client = null; state = null
                }
            }
        }, "hyperconnect-server").apply { isDaemon = true; start() }
    }

    private fun read(c: BluetoothSocket) {
        val dec = HyperConnectProtocol.Decoder()
        val buf = ByteArray(1024)
        val inp = c.inputStream
        while (running) {
            val n = inp.read(buf)
            if (n < 0) break
            for (m in dec.feed(buf, n)) when (m) {
                is HyperConnectProtocol.State -> { state = m; receivedNs = System.nanoTime() }
                is HyperConnectProtocol.Hello -> { deviceName = m.name; leftHanded = m.leftHanded; status = "Conectado: ${m.name}" }
            }
        }
    }

    /** Buzzes the controller phone (the pointer reaching a button, a click). */
    fun vibrate(ms: Int, amplitude: Int = 120) {
        val c = client ?: return
        try { synchronized(this) { c.outputStream.write(HyperConnectProtocol.encode(HyperConnectProtocol.Vibrate(ms, amplitude))) } } catch (_: Throwable) {}
    }

    fun stop() {
        running = false
        runCatching { server?.close() }; runCatching { client?.close() }
        server = null; client = null; state = null
        status = "Desligado"
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
