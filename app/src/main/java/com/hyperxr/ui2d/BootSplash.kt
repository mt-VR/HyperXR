package com.hyperxr.ui2d

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hyperxr.R
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Played once per start of the app (not when coming back to it). */
@Volatile var bootSplashPlayed = false

/**
 * The phone-screen version of HyperXR's boot: stars streaming out of the centre and slowing
 * down, the HX logo drawn by a sweep of light with a glow, "HYPERXR" closing its letter spacing,
 * a hairline progress bar, then a flare and a fade into the app.
 */
@Composable
fun BootSplash(onDone: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < END) { withFrameNanos { t = (it - start) / 1e9f } }
        onDone()
    }
    fun sm(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val cover = if (t < EXIT) 1f else 1f - sm((t - EXIT) / (END - EXIT))
    val reveal = sm((t - 0.6f) / 1.3f)
    val word = sm((t - 1.5f) / 0.8f)
    val flare = exp(-((t - EXIT - 0.1f) / 0.3f).let { it * it })
    val stars = remember { List(220) { floatArrayOf(Math.random().toFloat() * 6.2832f, Math.random().toFloat(), 0.4f + Math.random().toFloat() * 0.6f) } }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.75f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")

    Box(Modifier.fillMaxSize().alpha(cover).background(Brush.radialGradient(listOf(Color(0xFF1B1238), Color(0xFF07060F))))) {
        // hyperspace: each star runs outward along its ray, streaking while the speed is high
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val maxR = size.maxDimension * 0.75f
            val speed = 0.15f + 3.2f * exp(-t * 1.8f)
            val travel = 0.12f * t + 1.8f * (1f - exp(-t * 1.8f))
            for (s in stars) {
                val p = ((s[1] + travel * s[2]) % 1f)
                val r = maxR * p * p
                val len = maxR * (0.004f + speed * 0.05f) * p * s[2]
                val dir = Offset(cos(s[0]), sin(s[0]))
                drawLine(Color(0.75f, 0.82f, 1f, (0.25f + 0.75f * p) * s[2]), c + dir * r, c + dir * (r + len), strokeWidth = 1.2f + 2f * p, cap = StrokeCap.Round)
            }
            drawCircle(Brush.radialGradient(listOf(Color(0.35f, 0.45f, 1f, 0.35f * pulse + flare * 0.8f), Color.Transparent), c, size.minDimension * (0.35f + flare * 0.5f)), radius = size.minDimension * (0.35f + flare * 0.5f), center = c)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // glow under the logo (blur needs Android 12; older phones get the sharp logo only)
                if (Build.VERSION.SDK_INT >= 31) Image(painterResource(R.drawable.hx_logo), null,
                    Modifier.width(260.dp).blur(22.dp).alpha(reveal * (0.65f + 0.35f * pulse + flare)))
                Image(painterResource(R.drawable.hx_logo), null,
                    Modifier.width(260.dp)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, scaleX = 1f + 0.12f * flare, scaleY = 1f + 0.12f * flare)
                        .drawWithContent {
                            // the sweep: the logo appears behind a bright band leaning with its slant
                            val edge = (-0.25f + reveal * 1.5f) * size.width
                            drawContent()
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = edge - 0.1f * size.width, endX = edge), blendMode = BlendMode.DstOut)
                            drawRect(Color.Black, topLeft = Offset(edge, 0f), blendMode = BlendMode.DstOut)
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFBFD8FF), Color.Transparent), startX = edge - 0.06f * size.width, endX = edge + 0.02f * size.width), blendMode = BlendMode.SrcAtop)
                        })
            }
            Spacer(Modifier.height(28.dp))
            Text("HYPERXR", color = Color(0xFFE6ECFF), fontSize = 22.sp, fontWeight = FontWeight.Light,
                letterSpacing = (0.42f + 0.5f * (1f - word)).em, modifier = Modifier.alpha(word * (1f - sm((t - EXIT) / 0.4f))))
            Spacer(Modifier.height(36.dp))
            Box(Modifier.width(180.dp).height(2.dp).alpha(sm((t - 0.4f) / 0.4f) * (1f - sm((t - EXIT) / 0.4f))).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(sm((t - 0.4f) / 2.2f)).height(2.dp).background(Brush.horizontalGradient(listOf(Color(0xFF6F86FF), Color(0xFFE0E8FF)))))
            }
        }
    }
}

private const val EXIT = 2.9f
private const val END = 3.5f
