package com.hyperxr.openxr

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Runs the HyperXR OpenXR runtime self test: a real OpenXR session through the runtime library,
 * shown in the viewer (side by side, lens-corrected) for a few seconds, then the report.
 */
class RuntimeTestActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 48, 48, 48)
            text = "Iniciando o runtime HyperMagic… coloque o celular no visor: marcadores coloridos ficam fixos no espaço ao redor."
        }
        setContentView(text)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 15)
        Thread({
            val report = try { OpenXrNative.nativeRuntimeSelfTest(this, seconds) } catch (t: Throwable) { "Falha: ${t.message}" }
            runOnUiThread { text.text = "Teste do runtime HyperMagic\n\n$report\n\nToque em voltar para sair." }
        }, "hx-runtime-test").start()
    }

    companion object { const val EXTRA_SECONDS = "seconds" }
}
