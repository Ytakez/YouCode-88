package com.opencode.ai.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Пружинная «кнопочная» реакция: элемент слегка сжимается под пальцем
 * и упруго возвращается. Не перехватывает нажатие, поэтому работает
 * вместе с onClick у Surface / IconButton / clickable.
 */
fun Modifier.pressScale(pressedScale: Float = 0.95f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 650f),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/**
 * Упругое появление: прозрачность + сдвиг + лёгкий zoom.
 * [index] даёт каскадную задержку, [enabled] = false показывает элемент сразу.
 */
fun Modifier.entrance(
    index: Int = 0,
    enabled: Boolean = true,
    fromY: Dp = 24.dp,
    fromX: Dp = 0.dp,
    stepMs: Int = 60
): Modifier = composed {
    val progress = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            delay((minOf(index, 8) * stepMs).toLong())
            progress.animateTo(1f, spring(dampingRatio = 0.74f, stiffness = 240f))
        }
    }
    this.graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * fromY.toPx()
        translationX = (1f - p) * fromX.toPx()
        val s = 0.92f + 0.08f * p
        scaleX = s
        scaleY = s
    }
}

/** Плавное «парение» вверх-вниз, бесконечно. */
fun Modifier.floating(amplitude: Dp = 7.dp, periodMs: Int = 3200): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "floating")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatingOffset"
    )
    this.graphicsLayer { translationY = offset * amplitude.toPx() }
}

/**
 * Живой фон: три мягких цветных пятна из динамической палитры Material You
 * медленно дрейфуют по экрану.
 */
fun Modifier.aurora(strength: Float = 1f): Modifier = composed {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    val transition = rememberInfiniteTransition(label = "aurora")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "auroraAngle"
    )
    this.drawBehind {
        val w = size.width
        val h = size.height
        val r = max(w, h) * 0.75f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c1.copy(alpha = 0.20f * strength), Color.Transparent),
                center = Offset(w * (0.22f + 0.18f * cos(angle)), h * (0.18f + 0.12f * sin(angle))),
                radius = r
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c2.copy(alpha = 0.16f * strength), Color.Transparent),
                center = Offset(w * (0.82f + 0.14f * sin(angle * 0.7f)), h * (0.55f + 0.16f * cos(angle * 0.7f))),
                radius = r * 0.9f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c3.copy(alpha = 0.13f * strength), Color.Transparent),
                center = Offset(w * (0.45f + 0.2f * cos(angle * 1.3f)), h * (0.95f + 0.06f * sin(angle * 1.3f))),
                radius = r * 0.8f
            )
        )
    }
}

/** Форма, которая постоянно «перетекает» (морфинг скруглений). */
@Composable
fun rememberMorphShape(): Shape {
    val transition = rememberInfiniteTransition(label = "morph")
    val a by transition.animateFloat(
        28f, 50f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse), "a"
    )
    val b by transition.animateFloat(
        50f, 30f, infiniteRepeatable(tween(4300, easing = FastOutSlowInEasing), RepeatMode.Reverse), "b"
    )
    val c by transition.animateFloat(
        34f, 50f, infiniteRepeatable(tween(5100, easing = FastOutSlowInEasing), RepeatMode.Reverse), "c"
    )
    val d by transition.animateFloat(
        50f, 28f, infiniteRepeatable(tween(4700, easing = FastOutSlowInEasing), RepeatMode.Reverse), "d"
    )
    return RoundedCornerShape(
        topStartPercent = a.toInt(),
        topEndPercent = b.toInt(),
        bottomEndPercent = c.toInt(),
        bottomStartPercent = d.toInt()
    )
}

/**
 * Живой логотип: морфящаяся форма, парение и пульсирующее свечение.
 */
@Composable
fun MorphingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = rememberMorphShape()
    val glowColor = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "orb")
    val glow by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbGlow"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "orbSpin"
    )

    Box(
        modifier = modifier
            .floating()
            .drawBehind {
                val radius = this.size.minDimension * (0.95f + 0.25f * glow)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.30f * glow), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer { rotationZ = spin }
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                )
        )
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}
