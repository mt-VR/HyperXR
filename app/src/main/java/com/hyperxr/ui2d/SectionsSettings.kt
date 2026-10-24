package com.hyperxr.ui2d

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.capabilities.XrModeResolver
import com.hyperxr.handtracking.HandTrackingService
import com.hyperxr.joycon.JoyConManager
import com.hyperxr.launcher.BuiltinApps
import com.hyperxr.launcher.LauncherModel
import com.hyperxr.settings.ControllerAction
import com.hyperxr.settings.HeadTrackingSource
import com.hyperxr.settings.HyperSettings
import com.hyperxr.settings.InferenceBackend
import com.hyperxr.settings.LauncherMode
import com.hyperxr.settings.XrModePreference

internal fun MainActivity.update(f: (HyperSettings) -> HyperSettings) { app.settings.update(f) }

@Composable
fun HomeSection(a: MainActivity) {
    val s = a.settingsState
    val caps = a.app.capabilities.last
    val decision = XrModeResolver.resolve(caps, s.xrMode)
    SectionCard("Experiência imersiva", "O HyperXR inicia em realidade mista quando o aparelho permite; senão, em VR SBS para o VR Box.") {
        Segmented(
            listOf("AUTO" to "Automático", "MIXED_REALITY" to "Realidade mista", "VR_SBS" to "VR SBS", "OPENXR_RUNTIME" to "Runtime OpenXR"),
            s.xrMode.name,
        ) { k -> a.app.settings.update { it.copy(xrMode = XrModePreference.valueOf(k)) } }
        KeyValue("Modo que será usado", decision.mode.name)
        KeyValue("Rastreamento de cabeça", decision.headTracking)
        decision.reasons.forEach { Note("• $it") }
        if (!a.hasPermission(Manifest.permission.CAMERA)) {
            Note("A câmera é necessária para realidade mista e hand tracking.", HxColors.warn)
            OutlinedButton(onClick = { a.requestRuntimePermissions() }) { Text("Conceder permissões") }
        }
        Button(onClick = { a.startXr() }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent), modifier = Modifier.padding(top = 6.dp)) {
            Text("Iniciar experiência XR", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        }
        Note("Coloque o celular no VR Box após iniciar. Toque na tela (ou no botão do visor) para selecionar.")
    }
    SteamVrCard(a)
    SectionCard("Resumo do aparelho") {
        KeyValue("Aparelho", "${caps.manufacturer} ${caps.model} • Android ${caps.androidRelease} (API ${caps.androidSdk})")
        KeyValue("SoC", "${caps.socManufacturer} ${caps.socModel}")
        KeyValue("Tela", "${caps.displayWidthPx}x${caps.displayHeightPx} @ ${"%.0f".format(caps.displayRefreshHz)} Hz, ${"%.0f".format(caps.displayXdpi)} dpi")
        CapabilityRow("ARCore", caps.arCore)
        CapabilityRow("Runtime OpenXR", caps.openXrRuntime)
        CapabilityRow("NNAPI", caps.nnapi)
        KeyValue("Controles conectados", caps.joyConsConnected.toString())
    }
}

@Composable
fun LauncherSection(a: MainActivity) {
    val s = a.settingsState.launcher
    val model = remember { LauncherModel { BuiltinApps.all } }
    SectionCard("Estilo do launcher", "A mesma preferência pode ser trocada dentro do XR (Ajustes rápidos ou barra da dock).") {
        Segmented(listOf("DOCK" to "Dock", "NEW" to "New"), s.mode.name) { k -> a.app.settings.setLauncherMode(LauncherMode.valueOf(k)) }
        Note(if (s.mode == LauncherMode.DOCK) "Dock: janelas lado a lado sobre a barra de tarefas (relógio, apps fixados, Biblioteca, Ajustes)."
        else "New: a Biblioteca fica à sua frente (fundo, pesquisa, categorias, grade de apps) com a barra de tarefas embaixo. Segure um app para fixá-lo na barra.")
        ToggleRow("Reabrir janelas ao entrar no XR", s.restoreWindows, "Abas do navegador e apps Android abertos voltam onde estavam") { v ->
            a.update { it.copy(launcher = it.launcher.copy(restoreWindows = v)) }
        }
        ToggleRow("Ambiente pelo horário", s.autoEnvironmentByTime, "Os ambientes 360 embutidos seguem o relógio: rua roxa à noite, cobertura no pôr do sol, estúdio no horário de trabalho, praça de manhã e no começo da noite") { v ->
            a.update { it.copy(launcher = it.launcher.copy(autoEnvironmentByTime = v)) }
        }
    }
    SectionCard("Emuladores embutidos (créditos)", "Núcleos open source compilados a partir do código-fonte. Nenhum jogo incluído.") {
        com.hyperxr.emulators.EmulatorCatalog.credits().forEach { Note(it) }
    }
    SectionCard("Apps fixados na dock", "Reordene ou remova. Para fixar novos apps use a Biblioteca no XR.") {
        model.dockApps(s).forEachIndexed { i, app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}. ${app.title}", Modifier.weight(1f), color = HxColors.text)
                OutlinedButton(onClick = { a.update { it.copy(launcher = model.move(it.launcher, app.id, i - 1)) } }, enabled = i > 0) { Text("↑") }
                OutlinedButton(onClick = { a.update { it.copy(launcher = model.move(it.launcher, app.id, i + 1)) } }, enabled = i < s.pinned.size - 1) { Text("↓") }
                OutlinedButton(onClick = { a.update { it.copy(launcher = model.unpin(it.launcher, app.id)) } }) { Text("Remover") }
            }
        }
        val unpinned = BuiltinApps.all.filter { it.id !in s.pinned }
        var choice by remember { mutableStateOf(unpinned.firstOrNull()) }
        if (choice != null && s.pinned.size < LauncherModel.MAX_DOCK) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow("Adicionar", unpinned, choice!!, { it.title }) { choice = it }
                Button(onClick = { a.update { it.copy(launcher = model.pin(it.launcher, choice!!.id)) } }) { Text("Fixar") }
            }
        }
    }
}

@Composable
fun GraphicsSection(a: MainActivity) {
    val g = a.settingsState.graphics
    SectionCard("Renderização") {
        SliderRow("Escala de resolução por olho", g.renderScale, 0.5f..1.2f, { "%.0f%%".format(it * 100) }) { v -> a.update { it.copy(graphics = it.graphics.copy(renderScale = v)) } }
        SliderRow("Campo de visão (FOV)", g.fovDegrees, 70f..110f, { "%.0f°".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(fovDegrees = v)) } }
        ToggleRow("Câmera em tela cheia (MR)", g.passthroughFill, "Preenche cada olho com a câmera. Desligado: tamanho angular real, com bordas") { v ->
            a.update { it.copy(graphics = it.graphics.copy(passthroughFill = v)) }
        }
        ToggleRow("Profundidade estéreo no conteúdo virtual em MR", g.stereoInMixedReality, "Desligado: mãos e painéis ficam exatamente sobre a imagem da câmera (que é mono)") { v ->
            a.update { it.copy(graphics = it.graphics.copy(stereoInMixedReality = v)) }
        }
        ChoiceRow("Taxa alvo", listOf(60, 72, 90, 120), g.targetFps, { "$it fps" }) { v -> a.update { it.copy(graphics = it.graphics.copy(targetFps = v)) } }
        Note("A taxa real é limitada pela tela (vsync) e pela GPU; veja Diagnóstico para medições.")
    }
    SectionCard("Limite de segurança (VR)") {
        val depth = a.app.capabilities.last.arCoreDepth
        ToggleRow("Alerta de limite", g.vrBoundary, "Em VR, a imagem da câmera aparece aos poucos perto de obstáculos reais") { v ->
            a.update { it.copy(graphics = it.graphics.copy(vrBoundary = v)) }
        }
        SliderRow("Distância do alerta", g.boundaryDistanceM, 0.5f..2.0f, { "%.1f m".format(it) }) { v ->
            a.update { it.copy(graphics = it.graphics.copy(boundaryDistanceM = v)) }
        }
        Note(
            if (depth.ok) "Usa a ARCore Depth API (alcance útil até ~2,5 m). Também ativa 6DoF e o rastreamento de mãos pela ARCore em VR."
            else "Requer a ARCore Depth API, que não foi confirmada neste aparelho (${depth.detail}). Sem ela, o alerta fica desligado.",
        )
    }
    SectionCard("Lentes do visor (VR Box)") {
        ToggleRow("Correção de distorção da lente", g.lensDistortion) { v -> a.update { it.copy(graphics = it.graphics.copy(lensDistortion = v)) } }
        ToggleRow("Timewarp (reajusta a imagem com o giroscópio antes de mostrar)", g.timewarp) { v -> a.update { it.copy(graphics = it.graphics.copy(timewarp = v)) } }
        SliderRow("Coeficiente k1", g.distortionK1, 0f..0.6f, { "%.2f".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(distortionK1 = v)) } }
        SliderRow("Coeficiente k2", g.distortionK2, 0f..0.6f, { "%.2f".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(distortionK2 = v)) } }
        SliderRow("Distância interpupilar (IPD)", g.ipdMm, 54f..74f, { "%.1f mm".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(ipdMm = v)) } }
        SliderRow("Separação entre lentes", g.lensSeparationMm, 50f..70f, { "%.1f mm".format(it) }) { v -> a.update { it.copy(graphics = it.graphics.copy(lensSeparationMm = v)) } }
    }
}

@Composable
fun PerformanceSection(a: MainActivity) {
    val p = a.settingsState.performance
    val caps = a.app.capabilities.last
    SectionCard("Desempenho e temperatura") {
        ToggleRow("Modo de desempenho sustentado", p.sustainedPerformanceMode, caps.sustainedPerformance.detail.ifEmpty { "Evita picos que causam throttling" }) { v -> a.update { it.copy(performance = it.performance.copy(sustainedPerformanceMode = v)) } }
        ToggleRow("Resolução dinâmica", p.dynamicResolution) { v -> a.update { it.copy(performance = it.performance.copy(dynamicResolution = v)) } }
        ToggleRow("Reagir ao aquecimento", p.thermalThrottleAware) { v -> a.update { it.copy(performance = it.performance.copy(thermalThrottleAware = v)) } }
        ToggleRow("Modo de baixa latência", p.lowLatencyMode) { v -> a.update { it.copy(performance = it.performance.copy(lowLatencyMode = v)) } }
        SliderRow("Inferências de mão por segundo", p.handInferenceHz.toFloat(), 10f..60f, { "%.0f Hz".format(it) }, steps = 9) { v -> a.update { it.copy(performance = it.performance.copy(handInferenceHz = v.toInt())) } }
    }
    DiagnosticsCard()
}

@Composable
fun HeadSection(a: MainActivity) {
    val h = a.settingsState.headTracking
    val caps = a.app.capabilities.last
    SectionCard("Rastreamento de cabeça") {
        ChoiceRow("Fonte", HeadTrackingSource.entries, h.source, { when (it) {
            HeadTrackingSource.AUTO -> "Automática"; HeadTrackingSource.ARCORE_6DOF -> "ARCore (6DoF)"
            HeadTrackingSource.ROTATION_VECTOR_3DOF -> "Sensores (3DoF)"; HeadTrackingSource.OPENXR_RUNTIME -> "Runtime OpenXR"
            HeadTrackingSource.VISUAL_GYRO -> "Giroscópio computacional (câmera)" } }) { v ->
            a.update { it.copy(headTracking = it.headTracking.copy(source = v)) }
        }
        SliderRow("Predição de movimento", h.predictionMs, 0f..40f, { "%.0f ms".format(it) }) { v -> a.update { it.copy(headTracking = it.headTracking.copy(predictionMs = v)) } }
        ToggleRow("Recentralizar ao iniciar", h.recenterOnStart) { v -> a.update { it.copy(headTracking = it.headTracking.copy(recenterOnStart = v)) } }
        CapabilityRow("Vetor de rotação", caps.rotationVector)
        CapabilityRow("Giroscópio", caps.gyroscope)
        CapabilityRow("ARCore (6DoF)", caps.arCore)
        Note("6DoF só é anunciado quando o ARCore rastreia de fato. Sem ARCore o rastreamento é 3DoF com modelo de pescoço.")
        Note("Giroscópio computacional: para celulares sem giroscópio. A câmera mede a rotação da cabeça (pontos da cena seguidos quadro a quadro, mãos e pessoas descartadas) e guarda um mapa das direções já vistas para corrigir o desvio; o acelerômetro mantém o horizonte. No Automático ele liga sozinho quando o celular não tem giroscópio. Precisa de luz e de um ambiente com detalhes (parede lisa não serve).")
    }
}

@Composable
fun HandsSection(a: MainActivity) {
    val h = a.settingsState.handTracking
    var bench by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    SectionCard("Hand tracking", "Câmera → MediaPipe Hand Landmarker (GPU) → profundidade → filtro temporal → gestos.") {
        ToggleRow("Ativado", h.enabled) { v -> a.update { it.copy(handTracking = it.handTracking.copy(enabled = v)) } }
        Button(onClick = { a.startActivity(android.content.Intent(a, com.hyperxr.handtracking.HandTestActivity::class.java)) }) { Text("Testar detecção (câmera + esqueleto)") }
        Note("Segure o celular com a câmera para as mãos: o teste mostra o quadro analisado, o esqueleto encontrado e quantos quadros têm mão. Se aqui a mão some, o problema é luz, distância ou a câmera coberta pelo visor.")
        val npuDevices = remember { runCatching { com.hyperxr.diagnostics.NnapiNative.devices() }.getOrDefault(emptyList()).filter { it.contains("|accelerator|") }.map { it.substringBefore('|') } }
        ChoiceRow("Motor de hand tracking", listOf(InferenceBackend.NNAPI, InferenceBackend.GPU, InferenceBackend.CPU),
            when (h.backend) { InferenceBackend.CPU -> InferenceBackend.CPU; InferenceBackend.NNAPI -> InferenceBackend.NNAPI; else -> InferenceBackend.GPU }, {
            when (it) { InferenceBackend.NNAPI -> "NPU (HyperXR)"; InferenceBackend.CPU -> "CPU (MediaPipe)"; else -> "GPU (MediaPipe)" } }) { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(backend = v)) }
        }
        Note("NPU: o rastreador próprio do HyperXR roda na NPU do celular via NNAPI " +
            (if (npuDevices.isEmpty()) "(nenhum acelerador NNAPI encontrado neste celular: ele usaria a CPU)" else "(acelerador: ${npuDevices.joinToString()})") +
            ", deixando a GPU livre para o VR. GPU: MediaPipe. Os dois passam pelo verificador que descarta pés, cortinas e outras pessoas.")
        ToggleRow("Usar ARCore Depth", h.useArCoreDepth, a.app.capabilities.last.arCoreDepth.detail) { v -> a.update { it.copy(handTracking = it.handTracking.copy(useArCoreDepth = v)) } }
        ToggleRow("Direct Touch", h.directTouch, "Tocar botões virtuais com o dedo indicador") { v -> a.update { it.copy(handTracking = it.handTracking.copy(directTouch = v)) } }
        listOf("Polegar (só espaço)", "Indicador", "Médio", "Anelar", "Mínimo").forEachIndexed { f, name ->
            ToggleRow("Teclado na mesa: $name", f in h.tableFingers) { v ->
                a.update { s -> val next = if (v) s.handTracking.tableFingers + f else s.handTracking.tableFingers - f
                    if (next.isEmpty()) s else s.copy(handTracking = s.handTracking.copy(tableFingers = next)) }
            }
        }
        ToggleRow("Profundidade por IA no teclado de mesa", h.aiDepth,
            "Depth Anything V2 na NPU confirma cada toque na mesa (o modelo, ~92 MB, é baixado uma vez)") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(aiDepth = v)) }
        }
        ToggleRow("Câmera a 60 fps acima da Depth API", h.prefer60FpsOverDepth,
            "Ligado: menos latência nas mãos; a Depth API só fica se o celular a oferecer a 60 fps. Desligado: Depth API mesmo a 30 fps") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(prefer60FpsOverDepth = v)) }
        }
        ToggleRow("Clicar apontando e empurrando", h.clickGesture == com.hyperxr.settings.ClickGesture.POINT_PUSH,
            "Ligado: aponte com o indicador e empurre o dedo para clicar (segure empurrado para arrastar). Desligado: pinça") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(clickGesture = if (v) com.hyperxr.settings.ClickGesture.POINT_PUSH else com.hyperxr.settings.ClickGesture.PINCH)) }
        }
        ChoiceRow("Visual das mãos", listOf(com.hyperxr.settings.HandVisual.AURA, com.hyperxr.settings.HandVisual.NONE), h.handVisual, {
            if (it == com.hyperxr.settings.HandVisual.NONE) "Ocultas" else "Contorno"
        }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(handVisual = v)) } }
        ToggleRow("Apontar com a bolinha", h.ballPointer, "Ligado: clica no que está sob a bolinha, como você vê. Desligado: raio que sai do ombro") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(ballPointer = v)) }
        }
        SliderRow("Inclinação do raio da mão", h.rayPitchDeg, 0f..45f, { "%.0f° para cima".format(it) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(rayPitchDeg = v)) } }
        Note("Maior = alcança janelas altas com a mão mais baixa. Se o cursor ficar preso na dock, aumente.")
        SliderRow("Filtro de Kalman: resposta", h.kalmanProcessNoise, 0.3f..30f, { "%.1f".format(it) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(kalmanProcessNoise = v)) } }
        Note("Kalman menor = mais suave; maior = segue movimentos rápidos. O One Euro abaixo remove o tremor restante.")
        SliderRow("Intensidade do contorno", h.auraOpacity, 0.2f..1f, { "%.0f%%".format(it * 100) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(auraOpacity = v)) } }
        SliderRow("Comprimento da sua mão (punho até a ponta do dedo médio)", h.handLengthCm, 14f..23f, { "%.1f cm".format(it) }) { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(handLengthCm = v)) }
        }
        ToggleRow("Medir a mão automaticamente com ARCore Depth", h.autoCalibrateHandSize,
            if (h.calibratedPalmMm > 0) "Palma medida: %.1f cm".format(h.calibratedPalmMm / 10) else "Ainda não medida: mostre a mão aberta de frente para a câmera") { v ->
            a.update { it.copy(handTracking = it.handTracking.copy(autoCalibrateHandSize = v)) }
        }
        if (h.calibratedPalmMm > 0) OutlinedButton(onClick = { a.update { it.copy(handTracking = it.handTracking.copy(calibratedPalmMm = 0f)) } }) { Text("Medir de novo") }
        Note("O tamanho da mão define a que distância ela está da câmera. Sem ARCore Depth, meça a mão com uma régua e ajuste acima.")
        SliderRow("Sensibilidade da pinça", h.pinchClose, 0.22f..0.60f, { "fecha abaixo de %.0f%% da palma".format(it * 100) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(pinchClose = v)) } }
        Note("Maior = a pinça fecha mais cedo (mais fácil). Use o teste de detecção para ver a porcentagem ao vivo.")
        SliderRow("Filtro: corte mínimo", h.filterMinCutoff, 0.2f..4f, { "%.2f Hz".format(it) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(filterMinCutoff = v)) } }
        SliderRow("Filtro: beta (resposta a velocidade)", h.filterBeta, 0f..0.2f, { "%.3f".format(it) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(filterBeta = v)) } }
        SliderRow("Predição", h.predictionMs, 0f..50f, { "%.0f ms".format(it) }) { v -> a.update { it.copy(handTracking = it.handTracking.copy(predictionMs = v)) } }
    }
    SectionCard("Modelo", "MediaPipe Hand Landmarker (float16, Apache-2.0), embutido no APK — não precisa importar nada.") {
        Button(enabled = !running, onClick = {
            running = true
            a.io.execute {
                val svc = HandTrackingService(a)
                val r = svc.benchmark()
                svc.shutdown()
                a.runOnUiThread { bench = r; running = false }
            }
        }) { Text(if (running) "Executando…" else "Benchmark GPU / CPU") }
        bench.forEach { Mono(it) }
    }
}

@Composable
fun ControlsSection(a: MainActivity) {
    val c = a.settingsState.controllers
    val connected = remember(a.refresh) { JoyConManager.connectedJoyCons() }
    SectionCard("Controles", "Qualquer controle: Joy-Con, DualShock 4, DualSense, Xbox, 8BitDo, gamepads Bluetooth/USB e o controle do VR Box (em modo jogo, teclado ou mouse). Pareie no Bluetooth do Android; o HyperXR detecta sozinho.") {
        ToggleRow("Usar controles", c.joyConEnabled) { v -> a.update { it.copy(controllers = it.controllers.copy(joyConEnabled = v)) } }
        if (connected.isEmpty()) Note("Nenhum controle conectado agora.") else connected.forEach { KeyValue("Conectado", it) }
        CapabilityRow("IMU do controle", a.app.capabilities.last.inputDeviceSensors)
        Note("Para onde o controle aponta vem sempre da mão rastreada (6DoF, sem drift): raio do ombro passando pela mão. O giroscópio do controle nunca é usado (é instável), mesmo quando ele tem um.")
        SliderRow("Velocidade do cursor (analógico)", c.stickCursorSpeed, 30f..160f, { "%.0f°/s".format(it) }) { v ->
            a.update { it.copy(controllers = it.controllers.copy(stickCursorSpeed = v)) }
        }
        Note("Em todos: o analógico (ou o direcional, ou o mouse do controle do VR Box) move o cursor, A / X do PlayStation clica (segure e mova para arrastar), B / Círculo volta e fecha. R ou ZR + analógico rola a página; o analógico esquerdo dos controles de dois analógicos também rola.")
        val actions = ControllerAction.entries
        fun name(x: ControllerAction) = when (x) {
            ControllerAction.SELECT -> "Selecionar"; ControllerAction.BACK -> "Voltar"; ControllerAction.MENU -> "Menu"
            ControllerAction.GRAB -> "Agarrar"; ControllerAction.RECENTER -> "Recentralizar"; ControllerAction.NONE -> "Nada"
        }
        ChoiceRow("Botão R", actions, c.buttonR, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonR = v)) } }
        ChoiceRow("Botão L", actions, c.buttonL, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonL = v)) } }
        ChoiceRow("Botão ZR", actions, c.buttonZR, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonZR = v)) } }
        ChoiceRow("Botão ZL", actions, c.buttonZL, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonZL = v)) } }
        ChoiceRow("Botão +", actions, c.buttonPlus, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonPlus = v)) } }
        ChoiceRow("Botão HOME", actions, c.buttonHome, ::name) { v -> a.update { it.copy(controllers = it.controllers.copy(buttonHome = v)) } }
        ToggleRow("Toque na tela seleciona (VR Box)", c.screenTapSelects) { v -> a.update { it.copy(controllers = it.controllers.copy(screenTapSelects = v)) } }
        ToggleRow("Clique automático ao apontar (olhar parado num botão)", c.dwellClick) { v -> a.update { it.copy(controllers = it.controllers.copy(dwellClick = v)) } }
    }
}

@Composable
fun AudioSection(a: MainActivity) {
    val s = a.settingsState.audio
    val caps = a.app.capabilities.last
    SectionCard("Áudio") {
        SliderRow("Volume principal", s.masterVolume, 0f..1f, { "%.0f%%".format(it * 100) }) { v -> a.update { it.copy(audio = it.audio.copy(masterVolume = v)) } }
        ToggleRow("Áudio espacial (pan estéreo + distância)", s.spatialAudio) { v -> a.update { it.copy(audio = it.audio.copy(spatialAudio = v)) } }
        ToggleRow("Saída de baixa latência", s.lowLatencyOutput) { v -> a.update { it.copy(audio = it.audio.copy(lowLatencyOutput = v)) } }
        ToggleRow("Sons da interface", s.uiSounds) { v -> a.update { it.copy(audio = it.audio.copy(uiSounds = v)) } }
        CapabilityRow("Baixa latência", caps.lowLatencyAudio)
        CapabilityRow("Pro audio", caps.proAudio)
        KeyValue("Saída nativa", "${caps.audioSampleRate} Hz, ${caps.audioFramesPerBuffer} frames/buffer")
    }
}

@Composable
fun AccessibilitySection(a: MainActivity) {
    val s = a.settingsState.accessibility
    SectionCard("Acessibilidade") {
        SliderRow("Escala do texto", s.textScale, 0.8f..1.6f, { "%.0f%%".format(it * 100) }) { v -> a.update { it.copy(accessibility = it.accessibility.copy(textScale = v)) } }
        ToggleRow("Alto contraste", s.highContrast) { v -> a.update { it.copy(accessibility = it.accessibility.copy(highContrast = v)) } }
        ToggleRow("Reduzir movimento", s.reduceMotion, "Desliga a flutuação dos ícones e animações amplas") { v -> a.update { it.copy(accessibility = it.accessibility.copy(reduceMotion = v)) } }
        ToggleRow("Modo sentado", s.seatedMode) { v -> a.update { it.copy(accessibility = it.accessibility.copy(seatedMode = v)) } }
        ToggleRow("Uso com uma mão", s.oneHandedMode) { v -> a.update { it.copy(accessibility = it.accessibility.copy(oneHandedMode = v)) } }
    }
}

@Composable
fun NetworkSection(a: MainActivity) {
    val s = a.settingsState.network
    val unmetered = remember(a.refresh) { a.app.environments.isUnmeteredNetwork() }
    SectionCard("Rede") {
        KeyValue("Rede atual", if (unmetered) "sem limite de dados (Wi-Fi)" else "limitada ou sem conexão")
        ToggleRow("Permitir downloads de ambientes", s.allowEnvironmentDownloads) { v -> a.update { it.copy(network = it.network.copy(allowEnvironmentDownloads = v)) } }
        ToggleRow("Baixar apenas em Wi-Fi", s.downloadsOnWifiOnly) { v -> a.update { it.copy(network = it.network.copy(downloadsOnWifiOnly = v)) } }
        ToggleRow("Apps web em modo desktop", s.webAppsDesktopMode, "Igual ao \"site para computador\" dos navegadores") { v -> a.update { it.copy(network = it.network.copy(webAppsDesktopMode = v)) } }
        Note("Pacotes .hyperx só acessam a rede com a permissão 'network' e apenas hosts HTTPS declarados no manifesto.")
    }
}

/** SteamVR: the phone becomes the headset of a Windows PC running the HyperXR PC app. */
@Composable
private fun SteamVrCard(a: MainActivity) {
    val prefs = com.hyperxr.steamvr.SteamVrActivity.prefs(a)
    var ip by remember { mutableStateOf(prefs.getString("pc_ip", "") ?: "") }
    SectionCard("SteamVR", "Jogue jogos do SteamVR do PC no celular. No PC: HyperXR PC.exe (pasta pc/ do projeto) com o IP deste celular.") {
        KeyValue("IP deste celular", com.hyperxr.steamvr.SteamVrContent.localIp() ?: "conecte o Wi-Fi")
        var profile by remember { mutableStateOf(com.hyperxr.steamvr.SteamVrPlanner.profile(a)) }
        Segmented(com.hyperxr.steamvr.SteamVrProfiles.Profile.entries.map { it.name to it.label }, profile.name) { k ->
            profile = com.hyperxr.steamvr.SteamVrProfiles.Profile.valueOf(k)
            com.hyperxr.steamvr.SteamVrPlanner.setProfile(a, profile)
        }
        val plan = remember(profile) { com.hyperxr.steamvr.SteamVrPlanner.plan(a, profile) }
        val codec = if (plan.codecs and com.hyperxr.steamvr.SteamVrProfiles.CODEC_HEVC != 0) "HEVC" else "H.264"
        KeyValue("Transmissão", "${plan.profile.label}: ${plan.eyeWidth * 2}x${plan.eyeHeight} a ${plan.refreshHz} Hz, $codec, até ${plan.maxMbps} Mb/s, mãos a ${plan.handHz} Hz")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.OutlinedTextField(value = ip, onValueChange = { ip = it.trim() }, singleLine = true, label = { Text("IP do PC") })
            Button(onClick = {
                prefs.edit().putString("pc_ip", ip).apply()
                a.startActivity(android.content.Intent(a, com.hyperxr.steamvr.SteamVrActivity::class.java).putExtra(com.hyperxr.steamvr.SteamVrActivity.EXTRA_PC_IP, ip))
            }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Conectar") }
        }
        Note("Use Wi-Fi 5 GHz. Os Joy-Cons viram controles do Quest 3 (posição pela mão); sem Joy-Con, as mãos são os controles. Joy-Con Home recentraliza.")
    }
}
