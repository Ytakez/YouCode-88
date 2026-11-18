package com.opencode.ai.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Grok-inspired serious palette on top of Material Design 3.
 * Cool neutrals, high contrast, restrained accents. Dynamic color
 * still works on Android 12+, but surfaces stay deliberately neutral.
 */

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2F4A7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF545F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEDF1),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E6)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FF),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF1E3A6E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF0B0C0E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color(0xFF060708),
    surfaceContainerLow = Color(0xFF141516),
    surfaceContainer = Color(0xFF1A1B1D),
    surfaceContainerHigh = Color(0xFF242528),
    surfaceContainerHighest = Color(0xFF2F3033)
)

/** Slightly tighter radii than the previous expressive set — cleaner, more serious. */
private val YouCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Forces neutral surfaces so the UI stays close to Grok's calm dark look
 * even when dynamic color pulls strong hues from the wallpaper.
 */
private fun ColorScheme.withNeutralSurfaces(dark: Boolean): ColorScheme =
    if (dark) copy(
        background = Color(0xFF0B0C0E),
        surface = Color(0xFF0B0C0E),
        surfaceTint = Color(0xFF0B0C0E),
        onBackground = Color(0xFFE2E2E6),
        onSurface = Color(0xFFE2E2E6),
        surfaceVariant = Color(0xFF2A2B2E),
        onSurfaceVariant = Color(0xFFB0B3BB),
        surfaceContainerLowest = Color(0xFF060708),
        surfaceContainerLow = Color(0xFF141516),
        surfaceContainer = Color(0xFF1A1B1D),
        surfaceContainerHigh = Color(0xFF242528),
        surfaceContainerHighest = Color(0xFF2F3033),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF3A3B3E)
    ) else copy(
        background = Color(0xFFF8F9FC),
        surface = Color(0xFFF8F9FC),
        surfaceTint = Color(0xFFF8F9FC),
        onBackground = Color(0xFF1A1C1E),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F3F7),
        surfaceContainer = Color(0xFFECEDF1),
        surfaceContainerHigh = Color(0xFFE6E8EC),
        surfaceContainerHighest = Color(0xFFE1E2E6),
        outline = Color(0xFF74777F),
        outlineVariant = Color(0xFFC4C6D0)
    )

@Composable
fun YouCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var paletteRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) paletteRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val baseScheme = when {
        paletteRevision >= 0 && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Neutral surfaces (Grok-like), accents stay from palette / dynamic color.
    val colorScheme = baseScheme.withNeutralSurfaces(darkTheme)

    val animatedScheme = colorScheme.copy(
        primary = animateColorAsState(colorScheme.primary, tween(420)).value,
        onPrimary = animateColorAsState(colorScheme.onPrimary, tween(420)).value,
        primaryContainer = animateColorAsState(colorScheme.primaryContainer, tween(420)).value,
        onPrimaryContainer = animateColorAsState(colorScheme.onPrimaryContainer, tween(420)).value,
        secondary = animateColorAsState(colorScheme.secondary, tween(420)).value,
        secondaryContainer = animateColorAsState(colorScheme.secondaryContainer, tween(420)).value,
        tertiary = animateColorAsState(colorScheme.tertiary, tween(420)).value,
        tertiaryContainer = animateColorAsState(colorScheme.tertiaryContainer, tween(420)).value,
        background = animateColorAsState(colorScheme.background, tween(420)).value,
        surface = animateColorAsState(colorScheme.surface, tween(420)).value,
        onSurface = animateColorAsState(colorScheme.onSurface, tween(420)).value,
        onBackground = animateColorAsState(colorScheme.onBackground, tween(420)).value,
        onSurfaceVariant = animateColorAsState(colorScheme.onSurfaceVariant, tween(420)).value,
        surfaceVariant = animateColorAsState(colorScheme.surfaceVariant, tween(420)).value,
        surfaceContainer = animateColorAsState(colorScheme.surfaceContainer, tween(420)).value,
        surfaceContainerLow = animateColorAsState(colorScheme.surfaceContainerLow, tween(420)).value,
        surfaceContainerHigh = animateColorAsState(colorScheme.surfaceContainerHigh, tween(420)).value,
        surfaceContainerHighest = animateColorAsState(colorScheme.surfaceContainerHighest, tween(420)).value,
        surfaceContainerLowest = animateColorAsState(colorScheme.surfaceContainerLowest, tween(420)).value,
        outline = animateColorAsState(colorScheme.outline, tween(420)).value,
        outlineVariant = animateColorAsState(colorScheme.outlineVariant, tween(420)).value
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = animatedScheme,
        typography = Typography,
        shapes = YouCodeShapes,
        content = content
    )
}
