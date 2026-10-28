package com.hyperxr.connect.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hyperxr.connect.HyperConnectProtocol as P
import java.io.OutputStream
import java.util.UUID

/**
 * HyperConnect on the controller phone: pick the HyperXR phone among the paired devices, then
 * hold this phone like a remote (screen up, top pointing ahead). Its orientation, gyroscope and
 * accelerometer go to HyperXR 100 times a second, with the touchpad and buttons on the screen and
 * the volume keys as trigger (down) and grip (up).
 */
class ControllerActivity : Activity(), SensorEventListener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private val rot = floatArrayOf(0f, 0f, 0f, 1f)
    private val gyro = FloatArray(3)
    private val accel = floatArrayOf(0f, 0f, 9.81f)
    @Volatile private var buttons = 0
    @Volatile private var touching = false
    @Volatile private var touchX = 0.5f
    @Volatile private var touchY = 0.5f
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    private var sender: Thread? = null
    private var deviceName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else showDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) showDevices()
        else message("O HyperConnect precisa da permissão de Bluetooth para falar com o HyperMagic.")
    }

    // ------------------------------------------------------------------ choose the HyperXR phone

    private fun message(s: String) = setContentView(TextView(this).apply { text = s; textSize = 18f; setTextColor(Color.WHITE); setPadding(48, 96, 48, 48); setBackgroundColor(BG) })

    @SuppressLint("MissingPermission")
    private fun showDevices() {
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null) { message("Este celular não tem Bluetooth."); return }
        if (!bt.isEnabled) { message("Ligue o Bluetooth e abra o HyperConnect de novo."); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48) }
        col.addView(text("HyperConnect", 26f, true))
        col.addView(text("Este celular vira o controle do HyperMagic. No celular do HyperMagic, deixe \"HyperConnect\" ligado em Ajustes > Mãos e controles. " +
            "Os dois precisam estar pareados nas configurações de Bluetooth. Escolha o celular do HyperMagic:", 16f, false))
        val devices = bt.bondedDevices.orEmpty().sortedBy { it.name ?: "" }
        if (devices.isEmpty()) col.addView(text("Nenhum aparelho pareado. Pareie com o celular do HyperMagic nas configurações de Bluetooth.", 16f, false))
        val last = getPreferences(0).getString("last", null)
        for (d in devices.sortedByDescending { it.address == last }) col.addView(Button(this).apply {
            text = (d.name ?: d.address) + if (d.address == last) "  (último usado)" else ""
            isAllCaps = false
            setOnClickListener { connect(d) }
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })
    }

    private fun text(s: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size; setTextColor(if (bold) Color.WHITE else Color.rgb(200, 202, 208)); setPadding(0, 12, 0, 24)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        message("Conectando a ${d.name ?: d.address}...")
        Thread {
            try {
                // a running discovery slows the connection down; stopping it needs BLUETOOTH_SCAN on
                // Android 12+, which this app does not ask for (it only connects to a paired phone)
                if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                    runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
                val s = d.createInsecureRfcommSocketToServiceRecord(UUID.fromString(P.SERVICE_UUID))
                s.connect()
                socket = s; deviceName = d.name ?: d.address
                getPreferences(0).edit().putString("last", d.address).apply()
                s.outputStream.write(P.encode(P.Hello(Build.MODEL, leftHanded = false)))
                ui.post { showController(); startStreaming(s) }
            } catch (t: Throwable) {
                ui.post {
                    message("Não conectou: ${t.message}\n\nConfira se o HyperMagic está aberto no outro celular com o HyperConnect ligado.")
                    ui.postDelayed({ showDevices() }, 3500)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ controller

    // ------------------------------------------------------------------ typing into HyperXR
    private var typing: android.widget.EditText? = null
    private var typed = ""

    /** The phone's own keyboard typing into the field open in HyperXR. */
    private fun typingBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 8, 24, 8); visibility = View.GONE }
        val edit = android.widget.EditText(this).apply {
            hint = "Digite aqui: vai para o campo aberto no HyperMagic"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(140, 142, 150)); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val now = s?.toString() ?: ""
                    val e = com.hyperxr.connect.TextDiff.between(typed, now)
                    typed = now
                    repeat(e.deletes) { send(P.encode(P.Key(KeyEvent.KEYCODE_DEL))) }
                    if (e.insert.isNotEmpty()) send(P.encode(P.Text(e.insert)))
                }
            })
            setOnEditorActionListener { _, _, _ -> send(P.encode(P.Key(KeyEvent.KEYCODE_ENTER))); typed = ""; setText(""); true }
        }
        typing = edit
        row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "Fechar"; isAllCaps = false; setOnClickListener { showTyping(false) } })
        return row
    }

    private fun showTyping(show: Boolean) {
        val e = typing ?: return
        (e.parent as? View)?.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (show) { typed = ""; e.setText(""); e.requestFocus(); imm?.showSoftInput(e, 0) } else imm?.hideSoftInputFromWindow(e.windowToken, 0)
    }

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        Thread { try { synchronized(sendLock) { s.outputStream.write(bytes) } } catch (_: Throwable) {} }.start()
    }
    private val sendLock = Any()

    private fun showController() {
        val pad = Pad()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        col.addView(typingBar(), LinearLayout.LayoutParams(-1, -2))
        col.addView(text("Conectado a $deviceName. Segure como um controle remoto, tela para cima. Segure HOME para centralizar.", 14f, false).apply { setPadding(36, 24, 36, 8) },
            LinearLayout.LayoutParams(-1, -2))
        col.addView(pad, LinearLayout.LayoutParams(-1, 0, 3f))
        fun row(vararg b: Pair<String, Int>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((label, bit) in b) addView(HoldButton(label, bit), LinearLayout.LayoutParams(0, -1, 1f))
        }
        col.addView(row("Voltar" to P.BACK, "Home" to P.HOME, "Menu" to P.MENU), LinearLayout.LayoutParams(-1, 0, 0.9f))
        col.addView(Button(this).apply { text = "Teclado"; isAllCaps = false; setOnClickListener { showTyping(true) } }, LinearLayout.LayoutParams(-1, -2))
        col.addView(row("Grip" to P.GRIP, "Gatilho" to P.TRIGGER), LinearLayout.LayoutParams(-1, 0, 1.3f))
        setContentView(col)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
    }

    private fun startStreaming(s: BluetoothSocket) {
        running = true
        (sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        val battery = getSystemService(BatteryManager::class.java)
        sender = Thread({
            var seq = 0
            val out: OutputStream = s.outputStream
            try {
                while (running) {
                    val st = synchronized(rot) {
                        P.State(seq++ and 0xffff, SystemClock.elapsedRealtimeNanos(), rot.copyOf(), gyro.copyOf(), accel.copyOf(),
                            buttons, touching, touchX, touchY, battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0)
                    }
                    synchronized(sendLock) { out.write(P.encode(st)) }
                    Thread.sleep(10)
                }
            } catch (t: Throwable) { if (running) ui.post { disconnected() } }
        }, "hyperconnect-send").apply { priority = Thread.MAX_PRIORITY; start() }
        // HyperXR's vibrations (the pointer reaching a button, a click)
        Thread({
            val dec = P.Decoder(); val buf = ByteArray(256)
            try {
                val inp = s.inputStream
                while (running) {
                    val n = inp.read(buf); if (n < 0) break
                    for (m in dec.feed(buf, n)) when (m) {
                        is P.Vibrate -> vibrate(m.ms.toLong(), m.amplitude)
                        is P.Keyboard -> ui.post { showTyping(m.open) }
                    }
                }
            } catch (_: Throwable) {}
        }, "hyperconnect-recv").start()
    }

    private fun disconnected() {
        stopStreaming()
        message("A conexão caiu. Voltando para a lista...")
        ui.postDelayed({ showDevices() }, 1500)
    }

    private fun stopStreaming() {
        running = false
        sensors.unregisterListener(this)
        runCatching { socket?.close() }; socket = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        synchronized(rot) {
            when (e.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    val q = FloatArray(4); SensorManager.getQuaternionFromVector(q, e.values)   // w, x, y, z
                    rot[0] = q[1]; rot[1] = q[2]; rot[2] = q[3]; rot[3] = q[0]
                }
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(e.values, 0, gyro, 0, 3)
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(e.values, 0, accel, 0, 3)
            }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun press(bit: Int, down: Boolean) {
        val before = buttons
        buttons = if (down) buttons or bit else buttons and bit.inv()
        if (down && before and bit == 0) vibrate(12, 160)
    }

    // volume keys: physical buttons under the finger - down = trigger, up = grip
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (socket == null) return super.dispatchKeyEvent(e)
        val bit = when (e.keyCode) { KeyEvent.KEYCODE_VOLUME_DOWN -> P.VOL_DOWN; KeyEvent.KEYCODE_VOLUME_UP -> P.VOL_UP; else -> return super.dispatchKeyEvent(e) }
        if (e.repeatCount == 0) press(bit, e.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun vibrate(ms: Long, amplitude: Int) {
        val v = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255))) else @Suppress("DEPRECATION") v.vibrate(ms)
    }

    override fun onDestroy() { stopStreaming(); super.onDestroy() }

    /** The touchpad: touching sends the finger position; a quick tap is a click. */
    private inner class Pad : View(this@ControllerActivity) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 46, 52) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 170, 255) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 152, 160); textSize = 40f; textAlign = Paint.Align.CENTER }
        private var downAt = 0L; private var downX = 0f; private var downY = 0f
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            r.set(24f, 16f, width - 24f, height - 16f)
            c.drawRoundRect(r, 48f, 48f, bg)
            if (touching) c.drawCircle(r.left + touchX * r.width(), r.top + touchY * r.height(), 36f, dot)
            else c.drawText("Touchpad: arraste para rolar, toque para clicar", width / 2f, height / 2f, label)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = ((e.x - 24f) / (width - 48f)).coerceIn(0f, 1f); val y = ((e.y - 16f) / (height - 32f)).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; touchX = x; touchY = y; downAt = SystemClock.uptimeMillis(); downX = x; downY = y }
                MotionEvent.ACTION_MOVE -> { touchX = x; touchY = y }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    val tap = e.actionMasked == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 220 &&
                        kotlin.math.abs(x - downX) < 0.05f && kotlin.math.abs(y - downY) < 0.05f
                    if (tap) { press(P.CLICK, true); ui.postDelayed({ press(P.CLICK, false) }, 60) }
                }
            }
            invalidate()
            return true
        }
    }

    /** A screen button held for as long as the finger stays on it. */
    private inner class HoldButton(label: String, private val bit: Int) : TextView(this@ControllerActivity) {
        init {
            text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 36f; setColor(Color.rgb(58, 60, 68)); setStroke(2, Color.rgb(80, 82, 90)) }
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 12, 12, 12)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); (layoutParams as? LinearLayout.LayoutParams)?.setMargins(12, 12, 12, 12) }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { press(bit, true); alpha = 0.6f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { press(bit, false); alpha = 1f }
            }
            return true
        }
    }

    companion object { private val BG = Color.rgb(24, 25, 28) }
}
