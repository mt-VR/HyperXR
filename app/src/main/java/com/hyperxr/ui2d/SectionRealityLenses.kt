package com.hyperxr.ui2d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.hyperxr.settings.GraphicsSettings
import com.hyperxr.viewers.ViewerCatalog

/**
 * RealityLenses: which headset the phone goes into. Picking one sets the lens calibration
 * (field of view, lens centres, distortion, screen-to-lens distance) for HyperXR, its OpenXR
 * runtime and the Cardboard/Daydream apps; Gráficos keeps the sliders for fine tuning.
 */
@Composable
fun RealityLensesSection(a: MainActivity) {
    val g = a.settingsState.graphics
    val current = ViewerCatalog.byId(g.viewerId)
    var query by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf<String?>(null) }

    SectionCard("RealityLenses", "Escolha o óculos VR em que você coloca o celular. O HyperMagic ajusta as lentes (campo de visão, distância entre elas e a distorção) para esse modelo, também nos apps OpenXR, Cardboard e Daydream.") {
        if (current != null) {
            KeyValue("Óculos atual", current.title)
            val tuned = tunedAway(g, current)
            KeyValue("Lentes", "${g.fovDegrees.toInt()}° · ${"%.1f".format(g.lensSeparationMm)} mm entre as lentes" + if (tuned) " (ajustado em Gráficos)" else "")
            if (tuned) Button(onClick = { apply(a, current) }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.surface2)) { Text("Voltar aos valores do ${current.name}") }
        } else KeyValue("Óculos atual", "Personalizado (valores de Gráficos)")
        Note("Os valores marcados como estimados partem das especificações do fabricante. Se a imagem ficar curva nas bordas ou dupla, ajuste fino em Gráficos > Lentes do visor.")
    }

    SectionCard("Modelos (${ViewerCatalog.all.size})") {
        OutlinedTextField(query, { query = it }, label = { Text("Pesquisar (marca, modelo, ano)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Todos", brand == null) { brand = null }
            ViewerCatalog.brands.forEach { b -> Chip(b, brand == b) { brand = if (brand == b) null else b } }
        }
        val list = ViewerCatalog.search(query, brand)
        if (list.isEmpty()) Note("Nenhum óculos com esse nome. Escolha o mais parecido (mesmo tamanho de lente) e ajuste em Gráficos.")
        list.forEach { v -> ViewerRow(v, v.id == g.viewerId) { apply(a, v) } }
    }
}

/** True when the sliders in Gráficos moved away from the chosen headset's values. */
private fun tunedAway(g: GraphicsSettings, v: ViewerCatalog.Viewer) =
    kotlin.math.abs(g.fovDegrees - v.fovDeg) > 0.5f || kotlin.math.abs(g.lensSeparationMm - v.lensSeparationMm) > 0.2f ||
        kotlin.math.abs(g.distortionK1 - v.k1) > 0.005f || kotlin.math.abs(g.distortionK2 - v.k2) > 0.005f

private fun apply(a: MainActivity, v: ViewerCatalog.Viewer) {
    a.update {
        it.copy(
            graphics = it.graphics.copy(
                viewerId = v.id, fovDegrees = v.fovDeg, lensSeparationMm = v.lensSeparationMm, distortionK1 = v.k1, distortionK2 = v.k2,
                screenToLensMm = v.screenToLensMm, trayToLensMm = v.trayToLensMm, lensDistortion = true,
            ),
            // a button that taps the screen through the case selects
            controllers = if (v.button == ViewerCatalog.Button.TOUCH) it.controllers.copy(screenTapSelects = true) else it.controllers,
        )
    }
    // the Cardboard / Daydream viewer file follows (when storage access was given)
    runCatching { com.hyperxr.compat.CompatLayer.writeCardboardParams(a) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) HxColors.accent else HxColors.surface2)
            .clickableNoRipple(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = HxColors.text, fontSize = 14.sp,
    )
}

@Composable
private fun ViewerRow(v: ViewerCatalog.Viewer, selected: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) HxColors.accent.copy(alpha = 0.18f) else HxColors.surface2)
            .border(if (selected) 2.dp else 0.dp, if (selected) HxColors.accent else Color.Transparent, shape)
            .clickableNoRipple(onPick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(v.title, color = HxColors.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("${v.year} · ${v.fovDeg.toInt()}° · lentes a ${"%.0f".format(v.lensSeparationMm)} mm" + (if (v.adjustableIpd) " (ajustável)" else "") +
                " · celular de ${"%.1f".format(v.phoneMin)}\" a ${"%.1f".format(v.phoneMax)}\"", color = HxColors.dim, fontSize = 13.sp)
            Text(v.button.label, color = HxColors.dim, fontSize = 13.sp)
            if (v.note.isNotEmpty()) Text(v.note, color = HxColors.warn, fontSize = 13.sp)
            Text(v.source.label, color = if (v.source == ViewerCatalog.Source.OFFICIAL) HxColors.ok else HxColors.dim, fontSize = 12.sp)
        }
        Text(if (selected) "Em uso" else "Usar", color = if (selected) HxColors.accent else HxColors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}
