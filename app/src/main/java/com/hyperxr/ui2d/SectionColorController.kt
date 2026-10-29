package com.hyperxr.ui2d

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.hyperxr.color.ColorCalibActivity
import com.hyperxr.color.ColorControllerModel
import com.hyperxr.color.ColorControllerStore

/**
 * Controle por cor: any controller (or a stick, a pen) with coloured stickers becomes a 6DoF
 * controller, tracked by the camera. The calibrator is where the spots are marked.
 */
@Composable
fun ColorControllerSection(a: MainActivity) {
    val c = a.settingsState.controllers
    var stamp by remember { mutableIntStateOf(0) }
    val models = remember(a.refresh, stamp) { ColorControllerStore.loadAll(a) }

    SectionCard("Controle por cor", "Cole adesivos ou fitas coloridas no controle (3 ou mais, de cores bem diferentes: vermelho, verde, azul, amarelo). A câmera acha as cores e calcula onde o controle está e para onde ele aponta. A separação das cores roda na GPU, num shader.") {
        ToggleRow("Usar o controle por cor", c.colorController, detail = "Usa a câmera do hand tracking; funciona no modo VR e no MR") { v ->
            a.update { it.copy(controllers = it.controllers.copy(colorController = v)) }
        }
        KeyValue("Controle esquerdo", describe(models[0]))
        KeyValue("Controle direito", describe(models[1]))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.startActivity(Intent(a, ColorCalibActivity::class.java)) }) { Text("Abrir o calibrador") }
            if (models[0].usable) OutlinedButton(onClick = { ColorControllerStore.delete(a, 0); stamp++ }) { Text("Apagar o esquerdo") }
            if (models[1].usable) OutlinedButton(onClick = { ColorControllerStore.delete(a, 1); stamp++ }) { Text("Apagar o direito") }
        }
        Note("Dois controles ao mesmo tempo (por exemplo dois Joy-Cons): calibre um como esquerdo e o outro como direito, com cores diferentes em cada um. Os dois juntos podem ter até 8 pontos; com 3 ou 4 em cada um já é 6DoF completo. Um Joy-Con conectado continua com os botões e o analógico dele, e a posição vem das cores.")
    }

    SectionCard("Como calibrar", "No calibrador:") {
        Note("1. Segure o controle de frente para a câmera, a uns 40 cm, com todos os pontos à vista.")
        Note("2. Toque em cada ponto colorido (a imagem congela no primeiro toque). A cor, o lugar e o tamanho são medidos sozinhos.")
        Note("3. Toque em \"Tocar marca: apontador\" e depois no lugar para onde o apontador deve ir, ou deixe \"Apontar para a frente\".")
        Note("4. Meça com uma régua a distância entre os pontos 1 e 2 e ajuste em cm. Escolha qual controle está calibrando (esquerdo ou direito) e salve.")
        Note("5. Um ponto marcado como botão vira clique quando você cobre ele com o dedo.")
        Note("Luz boa e cores fortes ajudam. Com 1 ponto só a posição é rastreada; com 2, posição e direção; com 3 ou mais, os 6 graus de liberdade.", HxColors.dim)
    }
}

private fun describe(m: ColorControllerModel): String = when {
    !m.usable -> "ainda não calibrado"
    else -> "${m.markers.size} " + (if (m.markers.size == 1) "ponto" else "pontos") + " · " +
        (if (m.markers.any { it.button }) "com botão" else "sem botão")
}
