package com.hyperxr.ui2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.darkColorScheme
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
import com.hyperxr.capabilities.Capability
import com.hyperxr.capabilities.Support

/** HyperXR identity colours for the 2D management UI. */
object HxColors {
    // Graphite greys and one blue, matching the XR panels (XrTheme).
    val bg = Color(0xFF18191B)
    val surface = Color(0xFF26272A)
    val surface2 = Color(0xFF34363A)
    val accent = Color(0xFF2874F0)
    val accent2 = Color(0xFF5494FF)
    val text = Color(0xFFF2F3F5)
    val dim = Color(0xFFA6A9AF)
    val ok = Color(0xFF5ADC96)
    val warn = Color(0xFFFFC266)
    val bad = Color(0xFFFF606E)
}

val HxColorScheme = darkColorScheme(
    primary = HxColors.accent, secondary = HxColors.accent2, background = HxColors.bg, surface = HxColors.surface,
    onPrimary = Color.White, onSurface = HxColors.text, onBackground = HxColors.text, surfaceVariant = HxColors.surface2,
)

@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        colors = CardDefaults.cardColors(containerColor = HxColors.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = HxColors.text)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = HxColors.dim)
            content()
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, detail: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) HxColors.text else HxColors.dim)
            if (detail != null) Text(detail, fontSize = 12.sp, color = HxColors.dim)
        }
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, steps: Int = 0, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row { Text(label, Modifier.weight(1f), color = HxColors.text); Text(format(local), color = HxColors.accent) }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

@Composable
fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = HxColors.text)
        OutlinedButton(onClick = { open = true }) { Text(name(selected)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(HxColors.surface2).padding(4.dp)) {
        options.forEach { (key, label) ->
            val sel = key == selected
            Text(
                label,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) HxColors.accent else Color.Transparent)
                    .clickableNoRipple { onSelect(key) }.padding(horizontal = 18.dp, vertical = 10.dp),
                color = if (sel) Color.White else HxColors.dim, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun CapabilityRow(name: String, c: Capability) {
    val (color, label) = when (c.support) {
        Support.SUPPORTED -> HxColors.ok to "disponível"
        Support.UNSUPPORTED -> HxColors.bad to "indisponível"
        Support.NEEDS_PERMISSION -> HxColors.warn to "precisa de permissão"
        Support.NEEDS_INSTALL -> HxColors.warn to "precisa instalar"
        Support.UNKNOWN -> HxColors.dim to "desconhecido"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(name, Modifier.width(220.dp), color = HxColors.text)
        Text(label, Modifier.width(150.dp), color = color, fontWeight = FontWeight.SemiBold)
        Text(c.detail, Modifier.weight(1f), color = HxColors.dim, fontSize = 13.sp)
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(220.dp), color = HxColors.dim)
        Text(v, Modifier.weight(1f), color = HxColors.text)
    }
}

@Composable
fun Note(text: String, color: Color = HxColors.dim) { Text(text, fontSize = 13.sp, color = color) }

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.padding(top = h.dp))

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, onClick = onClick)

@Composable
fun Mono(text: String) = Text(text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, color = HxColors.text,
    style = MaterialTheme.typography.bodySmall)

/** Rounded squares everywhere (cards, menus, fields), not pills. */
val HxShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

/** The language picker: automatic (the phone's language) or one of HyperMagic's languages. */
@Composable
fun LanguagePicker(a: MainActivity) {
    val opts = listOf("" to "Automático") + com.hyperxr.i18n.I18n.LANGUAGES
    Segmented(opts, a.settingsState.language) { code -> a.update { it.copy(language = code) } }
}
