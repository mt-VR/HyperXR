package com.hyperxr.ui2d

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hyperxr.compat.CompatLayer
import com.hyperxr.connect.HyperConnectProtocol
import com.hyperxr.connect.HyperConnectServer
import kotlinx.coroutines.delay

/**
 * HyperController: a second phone as the controller, over Bluetooth (the HyperConnect protocol).
 * The steps in order (send the app, pair, connect), the state of Bluetooth here, the paired
 * phones, the settings, and a live connection test while this page is open.
 */
@Composable
fun HyperControllerSection(a: MainActivity) {
    val c = a.settingsState.controllers
    val bt = remember { a.getSystemService(BluetoothManager::class.java)?.adapter }
    val permOk = Build.VERSION.SDK_INT < 31 || a.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    var note by remember { mutableStateOf("") }

    SectionCard("HyperController", "Transforme outro celular no controle do HyperMagic: a orientação dele aponta, a tela vira touchpad e botões, o volume vira gatilho e grip. Ele vibra quando o ponteiro chega num botão e abre o teclado dele quando um campo de texto abre no HyperMagic.") {
        ToggleRow("Usar o HyperController", c.hyperConnect) { v -> a.update { it.copy(controllers = it.controllers.copy(hyperConnect = v)) } }
        ToggleRow("Vibração", c.hyperConnectHaptics, detail = "O celular controle vibra ao apontar um botão e a cada toque") { v ->
            a.update { it.copy(controllers = it.controllers.copy(hyperConnectHaptics = v)) }
        }
        if (note.isNotEmpty()) Note(note, HxColors.accent)
    }

    SectionCard("1. Instale o app no outro celular", "O app do controle já vem dentro do HyperMagic: envie por Nearby Share, Bluetooth ou um mensageiro e instale no outro celular.") {
        Button(onClick = { note = CompatLayer.shareHyperConnect(a) }) { Text("Enviar o app HyperController") }
    }

    SectionCard("2. Pareie os dois celulares", "Uma vez só, nas configurações de Bluetooth do Android.") {
        KeyValue("Bluetooth deste celular", when {
            bt == null -> "não existe neste aparelho"
            !permOk -> "sem permissão"
            bt.isEnabled -> "ligado"
            else -> "desligado"
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!permOk) Button(onClick = { a.requestRuntimePermissions() }) { Text("Permitir Bluetooth") }
            OutlinedButton(onClick = { runCatching { a.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Abrir o Bluetooth") }
            OutlinedButton(onClick = {
                // visible for two minutes so the other phone can find this one
                runCatching { a.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
                    .onFailure { note = "Não deu para ficar visível: ${it.message}" }
            }) { Text("Ficar visível (2 min)") }
        }
        val paired = remember(a.refresh, permOk) { pairedNames(bt, permOk) }
        if (paired.isEmpty()) Note("Nenhum aparelho pareado ainda.") else paired.forEach { KeyValue("Pareado", it) }
    }

    SectionCard("3. Conecte", "Abra o app no celular controle e escolha este celular na lista. No modo VR o HyperMagic espera a conexão sozinho; aqui você pode testar agora.") {
        ConnectionTest(a, permOk && bt?.isEnabled == true)
        Note("Segure como um controle remoto: tela para cima, ponta para a frente. Ao conectar, a direção é alinhada com a sua cabeça; segure Home para realinhar e toque em Home para recentralizar a visão.")
    }
}

@SuppressLint("MissingPermission")
private fun pairedNames(bt: BluetoothAdapter?, permOk: Boolean): List<String> =
    if (bt == null || !permOk) emptyList() else runCatching { bt.bondedDevices.map { it.name ?: it.address } }.getOrDefault(emptyList())

/** Runs the HyperController server while the test is on (and this page open), showing what arrives. */
@Composable
private fun ConnectionTest(a: MainActivity, ready: Boolean) {
    var on by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val server = remember { HyperConnectServer(a) }
    DisposableEffect(on) {
        if (on) server.start()
        onDispose { server.stop() }
    }
    // leaving the settings (opening the VR mode) ends the test: the VR mode waits for the phone itself
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) on = false }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(on) { while (on) { delay(200); tick++ } }
    if (!on) {
        Button(onClick = { on = true }, enabled = ready) { Text("Testar a conexão agora") }
        if (!ready) Note("Ligue o Bluetooth e permita o acesso para testar.")
        return
    }
    @Suppress("UNUSED_EXPRESSION") tick
    KeyValue("Estado", server.status)
    val s = server.state
    if (server.connected && s != null) {
        KeyValue("Celular", server.deviceName + if (server.leftHanded) " (canhoto)" else "")
        KeyValue("Bateria", "${s.battery}%")
        KeyValue("Apertado", buttonsText(s.buttons) + if (s.touching) " · tocando o touchpad" else "")
    }
    OutlinedButton(onClick = { on = false }) { Text("Parar o teste") }
    Note("O teste para sozinho quando você sai desta tela ou abre o modo VR.")
}

private fun buttonsText(b: Int): String = buildList {
    if (b and HyperConnectProtocol.TRIGGER != 0) add("gatilho")
    if (b and HyperConnectProtocol.CLICK != 0) add("clique")
    if (b and HyperConnectProtocol.BACK != 0) add("voltar")
    if (b and HyperConnectProtocol.MENU != 0) add("menu")
    if (b and HyperConnectProtocol.HOME != 0) add("home")
    if (b and HyperConnectProtocol.GRIP != 0) add("grip")
}.ifEmpty { listOf("nada") }.joinToString(", ")
