package app.away

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The Away palette: near-black with indigo, violet and mint light bleeding through glass panels. */
object Glow {
    val Night = Color(0xFF07080D)
    val Indigo = Color(0xFF5B6CFF)
    val Violet = Color(0xFFA06BFF)
    val Mint = Color(0xFF5CE1C8)
    val Amber = Color(0xFFFFC46B)
    val Rose = Color(0xFFFF7D8E)
    val Ink = Color(0xFFEDEEF6)
    val InkDim = Color(0xFFA6A9BD)
}

private val AwayColors = darkColorScheme(
    primary = Color(0xFF9FB0FF), onPrimary = Color(0xFF0E1550),
    primaryContainer = Color(0xFF28316E), onPrimaryContainer = Color(0xFFDCE0FF),
    secondary = Color(0xFFC3A6FF), onSecondary = Color(0xFF2B1559),
    secondaryContainer = Color(0xFF2A2447), onSecondaryContainer = Color(0xFFE5DAFF),
    tertiary = Color(0xFF6FE3D2), onTertiary = Color(0xFF00382F),
    background = Glow.Night, onBackground = Glow.Ink,
    surface = Glow.Night, onSurface = Glow.Ink, onSurfaceVariant = Glow.InkDim,
    surfaceContainer = Color(0xFF11131C), surfaceContainerHigh = Color(0xFF1B1E2B),
    surfaceContainerHighest = Color(0xFF242838), surfaceVariant = Color(0xFF1B1E2B),
    outline = Color(0xFF4A4E66), outlineVariant = Color(0xFF2A2E42),
    error = Color(0xFFFF8A9A), onError = Color(0xFF4A0A16),
    errorContainer = Color(0xFF4A1F29), onErrorContainer = Color(0xFFFFD9DE),
)

private val AwayType = Typography().run {
    fun t(s: TextStyle, size: Int, w: FontWeight, track: Double = 0.0) =
        s.copy(fontFamily = FontFamily.SansSerif, fontSize = size.sp, fontWeight = w, letterSpacing = track.sp)
    copy(
        displayLarge = t(displayLarge, 64, FontWeight.ExtraLight, -2.0),
        headlineMedium = t(headlineMedium, 32, FontWeight.SemiBold, -0.8),
        titleLarge = t(titleLarge, 22, FontWeight.SemiBold, -0.3),
        titleMedium = t(titleMedium, 16, FontWeight.Medium, 0.0),
        bodyLarge = t(bodyLarge, 16, FontWeight.Normal),
        bodyMedium = t(bodyMedium, 14, FontWeight.Normal),
        bodySmall = t(bodySmall, 12, FontWeight.Normal),
        labelLarge = t(labelLarge, 14, FontWeight.Medium, 0.2),
        labelMedium = t(labelMedium, 12, FontWeight.Medium, 0.4),
    )
}

@Composable
fun AwayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AwayColors, typography = AwayType) {
        // Without a Surface around it, text would fall back to black on our dark glass.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Glow.Ink, content = content,
        )
    }
}

/** Slow drifting colour behind everything, so the glass panels above it have something to refract. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(17000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Glow.Night)
        val r = size.width * 1.0f
        drawRect(
            Brush.radialGradient(
                listOf(Glow.Indigo.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(size.width * (0.05f + 0.55f * a), size.height * (0.06f + 0.10f * b)), radius = r,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Glow.Violet.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * (0.95f - 0.5f * b), size.height * (0.55f + 0.12f * a)), radius = r,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Glow.Mint.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * (0.2f + 0.3f * a), size.height * 0.98f), radius = r * 0.8f,
            ),
        )
    }
}

/** How far the page this composable lives on is from the centre of the pager: 0 settled, +-1 a page away. */
val LocalPageOffset = compositionLocalOf<() -> Float> { { 0f } }

/**
 * Cards trail each other as you swipe between tabs: the further down the page, the later it moves,
 * so the screen peels in layers instead of sliding as one slab.
 */
fun Modifier.cascade(index: Int): Modifier = composed {
    val offset = LocalPageOffset.current
    graphicsLayer {
        translationX = offset() * size.width * 0.10f * index.coerceIn(0, 6)
    }
}

/** Fade and rise on first appearance, one card after another. */
fun Modifier.staggerIn(index: Int): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * 36.dp.toPx()
    }
}

/** Dips and springs back while pressed. Pass the same source to the clickable. */
fun Modifier.pressScale(source: MutableInteractionSource, to: Float = 0.97f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) to else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Frosted surface: faint white fill with a light-catching hairline edge. */
fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp), fill: Float = 0.06f): Modifier =
    this.clip(shape)
        .background(Color.White.copy(alpha = fill))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f))), shape)

data class NavItem(val label: String, val on: ImageVector, val off: ImageVector)

/** Floating glass tab bar whose highlight glides with your finger as you swipe between pages. */
@Composable
fun GlassNav(pager: PagerState, items: List<NavItem>, onSelect: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(34.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(70.dp).clip(shape)
                .background(Color(0xE60E101A))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))), shape),
        ) {
            val itemW = maxWidth / items.size
            val pos = pager.currentPage + pager.currentPageOffsetFraction
            Box(
                Modifier.offset(x = itemW * pos + 5.dp, y = 6.dp).size(itemW - 10.dp, 58.dp)
                    .clip(RoundedCornerShape(29.dp))
                    .background(Brush.horizontalGradient(listOf(Glow.Indigo.copy(alpha = 0.60f), Glow.Violet.copy(alpha = 0.50f))))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(29.dp)),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item ->
                    val sel = (1f - abs(pos - i)).coerceIn(0f, 1f)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        val tint = lerp(Glow.InkDim, Color.White, sel)
                        Icon(
                            if (sel > 0.5f) item.on else item.off, null, Modifier.size(24.dp).graphicsLayer {
                                val s = 1f + 0.18f * sel
                                scaleX = s; scaleY = s; translationY = -2.dp.toPx() * sel
                            },
                            tint = tint,
                        )
                        Text(item.label, color = tint, style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** A glowing progress ring: the sweep fills toward the next milestone and breathes softly. */
@Composable
fun StreakRing(progress: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(1600, easing = FastOutSlowInEasing), label = "ring")
    val t = rememberInfiniteTransition(label = "ringfx")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke * 1.8f
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawCircle(
                Brush.radialGradient(listOf(Glow.Violet.copy(alpha = 0.20f * pulse), Color.Transparent), radius = size.minDimension / 2),
            )
            drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, box, style = Stroke(stroke))
            val sweep = 360f * p
            if (sweep > 0.5f) {
                val brush = Brush.sweepGradient(listOf(Glow.Indigo, Glow.Violet, Glow.Mint, Glow.Indigo), center = center)
                rotate(-90f) {
                    drawArc(brush, 0f, sweep, false, tl, box, alpha = 0.28f * pulse, style = Stroke(stroke * 2.4f, cap = StrokeCap.Round))
                    drawArc(brush, 0f, sweep, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        content()
    }
}
