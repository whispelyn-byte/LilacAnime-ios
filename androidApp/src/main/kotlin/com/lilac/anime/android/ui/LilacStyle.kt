package com.lilac.anime.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The desktop app's palette (styles.css :root and body.light), as iOS LilacStyle: one set for dark, one for light. */
@Immutable
data class LilacPalette(
    val dark: Boolean,
    val accent: Color,
    val background: Color,
    val card: Color,
    val card2: Color,
    val line: Color,
    val muted: Color,
    val text: Color,
)

val DarkPalette = LilacPalette(
    dark = true,
    accent = Color(0xFFC8A2C8), background = Color(0xFF121212), card = Color(0xFF1E1E1E), card2 = Color(0xFF252525),
    line = Color(0xFF2E2E2E), muted = Color(0xFFA3A0A8), text = Color(0xFFF0EDF6),
)
val LightPalette = LilacPalette(
    dark = false,
    accent = Color(0xFF8A6B8A), background = Color(0xFFFFFFFF), card = Color(0xFFF5F5F5), card2 = Color(0xFFECECEC),
    line = Color(0xFFE0E0E0), muted = Color(0xFF6F6F73), text = Color(0xFF1C1B1F),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

object LilacStyle {
    val score = Color(0xFFF4CF72)
    val onAccent = Color(0xFF1C1B1F)
    val radius = 6.dp
    val palette: LilacPalette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val card2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card2
    val line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
    val muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text

    /** iOS horizontalSizeClass == .regular: a tablet or a wide window. */
    val wide: Boolean @Composable get() = LocalConfiguration.current.screenWidthDp >= 700
    val gutter: Dp @Composable get() = if (wide) 32.dp else 16.dp
}

/** Desktop motion.css: 140 / 200 / 280 ms, cubic-bezier(.2, .8, .2, 1). */
object LilacMotion {
    val easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    fun <T> spec(duration: Int = 200) = tween<T>(durationMillis = duration, easing = easing)
}

@Composable
fun LilacTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (dark) androidx.compose.material3.darkColorScheme(
            primary = palette.accent, background = palette.background, surface = palette.card,
            onBackground = palette.text, onSurface = palette.text, onPrimary = LilacStyle.onAccent,
        ) else androidx.compose.material3.lightColorScheme(
            primary = palette.accent, background = palette.background, surface = palette.card,
            onBackground = palette.text, onSurface = palette.text, onPrimary = Color.White,
        ),
    ) {
        CompositionLocalProvider(LocalPalette provides palette, content = content)
    }
}
