package com.hyperxr.ui2d

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.files.ImportedGames
import com.hyperxr.shell.ShellBridge
import com.hyperxr.shell.ShizukuState
import com.hyperxr.xrapps.GameLog

/**
 * When a game started from the import tab is back in HyperMagic within seconds, it most likely
 * closed by itself: read its lines of the system log (through Shizuku, which may read it) and
 * show what the game and Android said.
 */
class GameCloseCheck(private val a: MainActivity) {
    var title by mutableStateOf<String?>(null)
    var summary by mutableStateOf<List<String>>(emptyList())
    var log by mutableStateOf("")
    var note by mutableStateOf<String?>(null)

    /** Called on resume: right after a launch, a return means the game closed. */
    fun onResume() {
        val (pkg, at) = ImportedGames.lastLaunch ?: return
        val secs = (System.currentTimeMillis() - at) / 1000.0
        if (secs > 25) return
        ImportedGames.lastLaunch = null
        show(pkg, "O jogo fechou depois de ${"%.0f".format(secs)} s")
    }

    fun show(pkg: String, heading: String) {
        title = heading; summary = emptyList(); log = ""; note = "Lendo o log do sistema…"
        if (ShellBridge.state(a) != ShizukuState.READY) {
            note = "Para ver o motivo, ative o Shizuku (Permissões e pacotes) e abra o jogo de novo. Sem ele o Android não deixa ler o log de outro app; " +
                "pelo PC: adb logcat -d | grep -i -E \"$pkg|AndroidRuntime|DEBUG\""
            return
        }
        ShellBridge.withService(a) { svc ->
            a.io.execute {
                val text = runCatching { svc?.gameLog(pkg) }.getOrNull()
                a.runOnUiThread {
                    if (text == null) { note = "O Shizuku não respondeu."; return@runOnUiThread }
                    val lines = text.lines().filter { it.isNotBlank() }
                    log = text
                    summary = GameLog.summary(lines)
                    note = when {
                        lines.isEmpty() -> "Nada do jogo no log (o Android pode ter apagado as linhas antigas)."
                        summary.isEmpty() -> "Nenhuma linha de erro conhecida; o log completo está abaixo."
                        else -> null
                    }
                }
            }
        }
    }
}

@Composable
fun GameCloseDialog(a: MainActivity) {
    val c = a.closeCheck
    val t = c.title ?: return
    AlertDialog(
        onDismissRequest = { c.title = null },
        title = { Text(t) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                c.note?.let { Text(it, color = HxColors.dim) }
                if (c.summary.isNotEmpty()) {
                    Text("Provável motivo:", color = HxColors.text)
                    c.summary.forEach { Text(it, fontSize = 12.sp, color = HxColors.warn, fontFamily = FontFamily.Monospace) }
                }
                if (c.log.isNotEmpty()) {
                    Text("Log do jogo:", color = HxColors.text, modifier = Modifier.heightIn(min = 24.dp))
                    Text(c.log.lines().takeLast(150).joinToString("\n"), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            Row {
                if (c.log.isNotEmpty()) TextButton(onClick = {
                    a.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("HyperMagic game log", (c.summary + "" + c.log.lines()).joinToString("\n")))
                    android.widget.Toast.makeText(a, "Log copiado", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copiar log") }
                TextButton(onClick = { c.title = null }) { Text("Fechar") }
            }
        },
    )
}
