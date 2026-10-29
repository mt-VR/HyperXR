package com.hyperxr.ui2d

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.shell.ShellBridge
import com.hyperxr.shell.ShizukuState
import com.hyperxr.viewers.ViewerCatalog

/**
 * The setup assistant on first run: permissions, the headset (RealityLenses), eyes and hands,
 * Shizuku for Android apps, and the gestures. Each step can be skipped; it can be run again
 * from Início. The gestures themselves are practised inside XR (XrTutorialPanel).
 */
@Composable
fun SetupWizard(a: MainActivity) {
    var step by remember { mutableIntStateOf(0) }
    val steps = listOf("Boas-vindas", "Permissões", "Seu óculos", "Olhos e mãos", "Apps Android", "Gestos")
    fun finish() = a.update { it.copy(onboarding = it.onboarding.copy(wizardDone = true)) }

    Column(Modifier.fillMaxSize().background(HxColors.bg)) {
        // progress: one dot per step
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Configuração inicial", color = HxColors.text, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            steps.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (i == step) 14.dp else 10.dp).clip(CircleShape)
                    .background(if (i <= step) HxColors.accent else HxColors.surface2))
            }
            TextButton(onClick = { finish() }, modifier = Modifier.padding(start = 16.dp)) { Text("Pular assistente", color = HxColors.dim) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
            Text("${step + 1}. ${steps[step]}", color = HxColors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            Column(Modifier.widthIn(max = 980.dp)) {
                when (step) {
                    0 -> { SectionCard("Idioma") { LanguagePicker(a) }; Welcome() }
                    1 -> Permissions(a)
                    2 -> RealityLensesSection(a)
                    3 -> EyesAndHands(a)
                    4 -> ShizukuStep(a)
                    else -> Gestures()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(Modifier.fillMaxWidth().background(HxColors.surface).padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Voltar") }
            Spacer(Modifier.weight(1f))
            if (step < steps.size - 1) Button(onClick = { step++ }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Próximo", fontSize = 17.sp) }
            else {
                OutlinedButton(onClick = { finish() }) { Text("Concluir") }
                Button(onClick = { finish(); a.startXr() }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Concluir e entrar no XR", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Text(FREE_APP_NOTICE, color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
    Para("O HyperMagic transforma o celular num óculos de realidade mista e VR: janelas e apps flutuando no seu quarto, controlados com as mãos pela câmera ou com qualquer controle.")
    Para("Em poucos passos você escolhe o seu óculos VR, ajusta a imagem aos seus olhos, prepara as mãos e, se quiser, os apps Android em janelas. Dá para pular qualquer passo e refazer tudo depois em Início.")
    Para("HyperMagic é feito em parceria com Samrat Beketov, criador do MagicXR.")
    Para("Dentro do XR, um tutorial curto ensina os gestos na prática: pinça, menu na palma e mão fechada.")
}

@Composable
private fun Permissions(a: MainActivity) {
    val camera = a.hasPermission(Manifest.permission.CAMERA)
    val bt = Build.VERSION.SDK_INT < 31 || a.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Suppress("UNUSED_EXPRESSION") a.refresh
    Para("A câmera é usada para a realidade mista e para ver as suas mãos. Nada sai do celular: as imagens são processadas no aparelho.")
    Status("Câmera (obrigatória)", camera)
    Status("Bluetooth (controles e HyperController)", bt)
    if (!camera || !bt) Button(onClick = { a.requestRuntimePermissions() }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent), modifier = Modifier.padding(top = 10.dp)) { Text("Conceder permissões") }
    else Note("Tudo certo.", HxColors.ok)
}

@Composable
private fun EyesAndHands(a: MainActivity) {
    val g = a.settingsState.graphics
    val h = a.settingsState.handTracking
    val viewer = ViewerCatalog.byId(g.viewerId)
    SectionCard("Olhos", "A distância entre as suas pupilas (IPD). Na dúvida, deixe 63 mm: é a média dos adultos.") {
        SliderRow("Distância entre as pupilas", g.ipdMm, 54f..74f, { "%.1f mm".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(ipdMm = v)) } }
        if (viewer?.adjustableIpd == true) Note("O ${viewer.title} tem ajuste das lentes: gire o botão de cima até as duas imagens virarem uma só, nítida.")
    }
    SectionCard("Mãos", "O HyperMagic mede a sua mão na primeira vez que ela aparece na câmera, para desenhá-la do tamanho certo e acertar a pinça.") {
        SliderRow("Comprimento da mão (punho à ponta do dedo médio)", h.handLengthCm, 15f..23f, { "%.1f cm".format(it) }) { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(handLengthCm = v)) }
        }
        ToggleRow("Medir a mão automaticamente", h.autoCalibrateHandSize, "Usa a profundidade (ARCore) quando o celular tem") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(autoCalibrateHandSize = v)) }
        }
        var done by remember { mutableStateOf(false) }
        OutlinedButton(onClick = {
            a.update { it.copy(handTracking = it.handTracking.copy(calibratedPalmMm = 0f, autoCalibrateHandSize = true)) }
            done = true
        }) { Text("Recalibrar as mãos") }
        if (done || h.calibratedPalmMm == 0f) Note("Na próxima entrada no XR, mostre as duas mãos abertas na frente da câmera por alguns segundos.", HxColors.ok)
        else Note("Mão medida: palma de ${"%.0f".format(h.calibratedPalmMm)} mm.")
    }
}

@Composable
private fun ShizukuStep(a: MainActivity) {
    @Suppress("UNUSED_EXPRESSION") a.refresh
    val state = remember(a.refresh) { runCatching { ShellBridge.state(a) }.getOrDefault(ShizukuState.NOT_INSTALLED) }
    Para("Opcional. Para abrir os seus apps Android (WhatsApp, Instagram, jogos...) em janelas dentro do XR, o HyperMagic usa o Shizuku, um app gratuito que dá essa permissão sem root.")
    Status("Shizuku", state == ShizukuState.READY, when (state) {
        ShizukuState.NOT_INSTALLED -> "não instalado"
        ShizukuState.NOT_RUNNING -> "instalado, mas parado: abra o Shizuku e inicie pela depuração sem fio"
        ShizukuState.NO_PERMISSION -> "rodando, falta permitir o HyperMagic"
        ShizukuState.READY -> "pronto"
    })
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
        when (state) {
            ShizukuState.NOT_INSTALLED -> Button(onClick = {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ShellBridge.SHIZUKU_PACKAGE}"))
                runCatching { a.startActivity(i) }
            }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Instalar o Shizuku") }
            ShizukuState.NOT_RUNNING -> Button(onClick = {
                a.packageManager.getLaunchIntentForPackage(ShellBridge.SHIZUKU_PACKAGE)?.let { runCatching { a.startActivity(it) } }
            }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Abrir o Shizuku") }
            ShizukuState.NO_PERMISSION -> Button(onClick = { ShellBridge.requestPermission { a.runOnUiThread { a.refresh++ } } },
                colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Permitir o HyperMagic") }
            ShizukuState.READY -> Unit
        }
        OutlinedButton(onClick = { a.refresh++ }) { Text("Verificar de novo") }
    }
    Note("Sem o Shizuku todo o resto funciona: só os apps Android em janelas ficam de fora.")
}

@Composable
private fun Gestures() {
    Gesture("Pinça", "Junte as pontas do polegar e do indicador: seleciona o que a bolinha do ponteiro está apontando, à distância.")
    Gesture("Toque direto", "Perto de um painel, toque nos botões com a ponta do indicador, como numa tela.")
    Gesture("Menu na palma", "Vire a palma da mão para cima e faça a pinça: abre o menu em círculo (voltar, recentralizar, teclado, voz, captura, biblioteca, janelas). Mova a mão até a opção e solte.")
    Gesture("Mão fechada", "Feche a mão sobre uma janela para segurá-la e mover pelo espaço.")
    Gesture("Controles", "Com qualquer controle: aponte com a mão que o segura, A (ou X no PlayStation) seleciona, B volta.")
    Gesture("Modo VR", "O botão Modo VR na dock troca a câmera por um ambiente virtual; o app Ambientes escolhe qual.")
    Note("Na primeira entrada no XR você pratica cada gesto, e o tutorial só avança quando você acerta.")
}

@Composable
private fun Para(t: String) = Text(t, color = HxColors.text, fontSize = 17.sp, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun Status(label: String, ok: Boolean, detail: String? = null) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) HxColors.ok else HxColors.warn))
        Text("  $label: " + (detail ?: if (ok) "permitida" else "falta permitir"), color = HxColors.text, fontSize = 16.sp)
    }
}

@Composable
private fun Gesture(name: String, how: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(HxColors.surface2).padding(14.dp)) {
        Text(name, color = HxColors.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text(how, color = HxColors.dim, fontSize = 15.sp)
    }
}
