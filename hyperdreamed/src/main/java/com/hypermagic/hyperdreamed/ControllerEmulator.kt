package com.hypermagic.hyperdreamed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import com.hyperxr.daydream.DaydreamBle
import java.util.UUID
import kotlin.math.sqrt

/**
 * This phone as a Daydream controller: a Bluetooth LE peripheral with the controller's service
 * (0xFE55) that notifies its 20-byte reports, built from this phone's orientation, gyroscope and
 * accelerometer (held like the controller: portrait, screen up, top forward) and the touchpad and
 * buttons on screen. Google VR Services pairs with it as with the real controller.
 */
@SuppressLint("MissingPermission")
class ControllerEmulator(private val ctx: Context) : SensorEventListener {
    val state = DaydreamBle.State()
    @Volatile var status = "Parado"; private set
    @Volatile var connectedName: String? = null; private set
    var onChange: (() -> Unit)? = null

    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var server: BluetoothGattServer? = null
    private var report: BluetoothGattCharacteristic? = null
    private val subscribed = java.util.concurrent.CopyOnWriteArraySet<BluetoothDevice>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var originalName: String? = null
    private val bytes = ByteArray(20)
    private val tmp = FloatArray(3)
    private var startMs = 0L

    val running get() = server != null

    /** Why it cannot run here, or null. */
    fun unsupported(): String? {
        val a = bt?.adapter ?: return "Este celular não tem Bluetooth"
        if (!a.isEnabled) return "Ligue o Bluetooth"
        if (a.bluetoothLeAdvertiser == null || !a.isMultipleAdvertisementSupported) return "O Bluetooth deste celular não consegue anunciar como periférico BLE (precisa disso para virar controle)"
        if (sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) == null && sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null)
            return "Este celular não tem giroscópio: o controle não saberia para onde aponta"
        return null
    }

    fun start(rename: Boolean): String? {
        unsupported()?.let { return it }
        val adapter = bt.adapter
        if (rename) {
            // Google VR Services looks for a device called "Daydream controller"
            originalName = adapter.name
            runCatching { adapter.setName(DaydreamBle.NAME) }
        }
        val s = bt.openGattServer(ctx, callback) ?: return "Não deu para abrir o servidor Bluetooth"
        server = s
        val svc = BluetoothGattService(UUID.fromString(DaydreamBle.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val r = BluetoothGattCharacteristic(UUID.fromString(DaydreamBle.REPORT),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        r.addDescriptor(BluetoothGattDescriptor(UUID.fromString(DaydreamBle.CLIENT_CONFIG), BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(r)
        report = r
        val info = BluetoothGattService(UUID.fromString(DaydreamBle.DEVICE_INFO), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        info.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(DaydreamBle.MANUFACTURER), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        info.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(DaydreamBle.MODEL), BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        val battery = BluetoothGattService(UUID.fromString(DaydreamBle.BATTERY_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        battery.addCharacteristic(BluetoothGattCharacteristic(UUID.fromString(DaydreamBle.BATTERY_LEVEL),
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        // services are added one at a time (the next after onServiceAdded)
        pendingServices.clear(); pendingServices += listOf(info, battery)
        s.addService(svc)

        val t = HandlerThread("daydream-controller").apply { start() }
        thread = t; handler = Handler(t.looper)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        startMs = System.currentTimeMillis()
        handler?.post(tick)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(rename).addServiceUuid(ParcelUuid.fromString(DaydreamBle.SERVICE)).build()
        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, advertising)
        setStatus("Anunciando como controle Daydream…")
        return null
    }

    private val pendingServices = ArrayList<BluetoothGattService>()

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        sm.unregisterListener(this)
        thread?.quitSafely(); thread = null; handler = null
        runCatching { bt.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertising) }
        runCatching { server?.close() }
        server = null; subscribed.clear(); connectedName = null
        originalName?.let { n -> runCatching { bt.adapter.setName(n) } }
        originalName = null
        setStatus("Parado")
    }

    private fun setStatus(s: String) { status = s; onChange?.invoke() }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            setStatus(when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Anúncio grande demais (nome do Bluetooth longo)"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Este celular não anuncia como periférico BLE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Bluetooth ocupado com outros anúncios; tente de novo"
                else -> "Falha ao anunciar (erro $errorCode)"
            })
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (pendingServices.isNotEmpty()) server?.addService(pendingServices.removeAt(0))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedName = runCatching { device.name }.getOrNull() ?: device.address
                setStatus("Conectado a $connectedName")
            } else {
                subscribed.remove(device)
                if (subscribed.isEmpty()) { connectedName = null; setStatus("Anunciando como controle Daydream…") }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val value = when (c.uuid.toString()) {
                DaydreamBle.MANUFACTURER -> "Google".toByteArray()
                DaydreamBle.MODEL -> DaydreamBle.NAME.toByteArray()
                DaydreamBle.BATTERY_LEVEL -> byteArrayOf(batteryPercent().toByte())
                DaydreamBle.REPORT -> bytes.copyOf()
                else -> ByteArray(0)
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0))
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor, preparedWrite: Boolean,
                                              responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (d.uuid.toString() == DaydreamBle.CLIENT_CONFIG) {
                if (value != null && value.isNotEmpty() && value[0].toInt() and 1 != 0) subscribed += device else subscribed -= device
                if (subscribed.isNotEmpty()) setStatus("Conectado a ${connectedName ?: "Serviços para RV"}: enviando movimento")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val v = if (device in subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }

    private fun batteryPercent(): Int = runCatching {
        ctx.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(80).coerceIn(0, 100)

    /** Sends a report about 60 times a second while someone listens. */
    private val tick = object : Runnable {
        override fun run() {
            val h = handler ?: return
            val c = report
            val s = server
            if (c != null && s != null && subscribed.isNotEmpty()) {
                synchronized(state) {
                    state.time = (((System.currentTimeMillis() - startMs) / 2) and 0x1ff).toInt()
                    state.seq = (state.seq + 1) and 0x1f
                    DaydreamBle.encode(state, bytes)
                }
                for (d in subscribed) notify(s, d, c, bytes)
            }
            h.postDelayed(this, 16)
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(s: BluetoothGattServer, d: BluetoothDevice, c: BluetoothGattCharacteristic, value: ByteArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, c, false, value)
            else { c.value = value; s.notifyCharacteristicChanged(d, c, false) }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(state) {
            when (e.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                    val w = if (e.values.size > 3) e.values[3] else sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
                    DaydreamBle.rotationVector(x, y, z, w, tmp)
                    DaydreamBle.phoneToController(tmp, state.orientation)
                }
                Sensor.TYPE_GYROSCOPE -> { tmp[0] = e.values[0]; tmp[1] = e.values[1]; tmp[2] = e.values[2]; DaydreamBle.phoneToController(tmp, state.gyro) }
                Sensor.TYPE_ACCELEROMETER -> { tmp[0] = e.values[0]; tmp[1] = e.values[1]; tmp[2] = e.values[2]; DaydreamBle.phoneToController(tmp, state.accel) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------ the screen's touchpad and buttons
    fun touch(x: Float, y: Float) = synchronized(state) { state.touching = true; state.touchX = x.coerceIn(0f, 1f); state.touchY = y.coerceIn(0f, 1f) }
    fun release() = synchronized(state) { state.touching = false }
    fun button(bit: Int, down: Boolean) = synchronized(state) { state.buttons = if (down) state.buttons or bit else state.buttons and bit.inv() }
}
