package com.hyperxr.vrcore

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hyperxr.daydream.DaydreamProtocol
import com.hyperxr.daydream.Quat
import rikka.shizuku.Shizuku
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2

/** Status and setup of the emulated Daydream controller, with a live view to check the axes. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var axes: Button
    private var stop: (() -> Unit)? = null
    private val held = java.util.TreeSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun text(size: Float, s: String = "") = TextView(this).apply { textSize = size; setTextColor(Color.WHITE); text = s; setPadding(0, 12, 0, 12) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }.also { col.addView(it) }
        col.addView(text(22f, "HyperXR Daydream"))
        col.addView(text(14f, "Faz apps Daydream e Cardboard (GVR) receberem um controle Daydream feito do seu Joy-Con ou gamepad, e a calibração do VR Box do HyperXR. " +
            "Toque no stick = touchpad, A = clique, X/Y = app, ZR = gatilho, R = grip, +/- = volume, HOME = home (segure para recentralizar)."))
        status = text(15f).also { col.addView(it) }
        button("Permitir acesso ao controle (Shizuku)") {
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ControllerInput.rescan()
                else Shizuku.requestPermission(1)
            } catch (t: Throwable) { status.text = "Instale e inicie o Shizuku primeiro" }
        }
        button("Procurar controle de novo") { ControllerInput.rescan() }
        button("Recentralizar") { ControllerInput.recenter() }
        axes = button("") { cycleAxes() }
        live = text(14f).also { col.addView(it) }
        col.addView(text(12f, "Sem Shizuku ou sem controle, o \"controle\" aponta para onde você olha e não tem botões. " +
            "O giroscópio do Joy-Con só aparece se o kernel do celular expuser o sensor (driver hid-nintendo); senão o controle segue a cabeça."))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(24, 25, 28)); addView(col, ViewGroup.LayoutParams(-1, -2)) })
        Shizuku.addRequestPermissionResultListener(permListener)
        showAxes()
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) ControllerInput.rescan()
    }

    private fun cycleAxes() {
        val cur = getSharedPreferences(ControllerInput.PREFS, 0).getString("axes", ControllerInput.AXIS_PRESETS[0])
        val i = ControllerInput.AXIS_PRESETS.indexOf(cur)
        ControllerInput.setAxisMap(this, ControllerInput.AXIS_PRESETS[(i + 1) % ControllerInput.AXIS_PRESETS.size])
        showAxes()
    }

    private fun showAxes() {
        val cur = getSharedPreferences(ControllerInput.PREFS, 0).getString("axes", ControllerInput.AXIS_PRESETS[0])
        axes.text = "Eixos do giroscópio: ${ControllerInput.AXIS_PRESETS.indexOf(cur).coerceAtLeast(0) + 1} de ${ControllerInput.AXIS_PRESETS.size} (toque para trocar)"
    }

    override fun onResume() {
        super.onResume()
        stop = ControllerInput.use(this) { f, _ -> synchronized(held) { for (b in f.buttons) if (b.down) held += b.button else held -= b.button } }
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        stop?.invoke(); stop = null
        super.onPause()
    }

    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permListener); super.onDestroy() }

    private val refresh = object : Runnable {
        override fun run() {
            val src = when (ControllerInput.source) {
                ControllerInput.Source.GAMEPAD_IMU -> "${ControllerInput.deviceName}, com giroscópio próprio"
                ControllerInput.Source.GAMEPAD -> "${ControllerInput.deviceName}, sem giroscópio (segue a cabeça)"
                ControllerInput.Source.GAZE -> "nenhum controle: segue a cabeça"
                ControllerInput.Source.NONE -> "parado"
            }
            status.text = "Controle: $src\n${ControllerInput.shizukuState}"
            ControllerInput.lastFrame?.let { f ->
                val q = f.orientation ?: floatArrayOf(0f, 0f, 0f, 1f)
                val fwd = Quat.rotate(q, floatArrayOf(0f, 0f, -1f))
                val yaw = Math.toDegrees(atan2(-fwd[0].toDouble(), -fwd[2].toDouble()))
                val pitch = Math.toDegrees(asin(fwd[1].toDouble().coerceIn(-1.0, 1.0)))
                val t = f.touch
                live.text = String.format(Locale.US, "Direção: %.0f° horizontal, %.0f° vertical\nTouchpad: %s\nBotões apertados: %s",
                    yaw, pitch,
                    if (t != null && t.action != DaydreamProtocol.TOUCH_UP) String.format(Locale.US, "%.2f, %.2f", t.x, t.y) else "solto",
                    synchronized(held) { held.joinToString { name(it) } }.ifEmpty { "nenhum" })
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun name(b: Int) = when (b) {
        DaydreamProtocol.BUTTON_CLICK -> "clique"; DaydreamProtocol.BUTTON_HOME -> "home"; DaydreamProtocol.BUTTON_APP -> "app"
        DaydreamProtocol.BUTTON_TRIGGER -> "gatilho"; DaydreamProtocol.BUTTON_GRIP -> "grip"
        DaydreamProtocol.BUTTON_VOLUME_UP -> "volume +"; DaydreamProtocol.BUTTON_VOLUME_DOWN -> "volume -"; else -> "botão $b"
    }
}
