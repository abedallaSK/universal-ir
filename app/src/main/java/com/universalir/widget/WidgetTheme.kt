package com.universalir.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.universalir.core.theme.Palette
import com.universalir.data.repository.ThemeMode

/** Widget colour roles derived from the app palette (core/theme/Theme.kt). */
private val WidgetLight = lightColorScheme(
    primary = Palette.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF0A2A80),
    secondary = Color(0xFF4A5468), onSecondary = Color.White,
    secondaryContainer = Palette.Fog, onSecondaryContainer = Palette.Ink,
    background = Palette.Mist, onBackground = Palette.Ink,
    surface = Palette.Cloud, onSurface = Palette.Ink,
    surfaceVariant = Palette.Fog, onSurfaceVariant = Color(0xFF4A5468),
    outline = Color(0xFFC5CBD5), outlineVariant = Color(0xFFE1E5EB),
    error = Palette.Error, onError = Color.White,
    tertiary = Color(0xFF0E9F9A), onTertiary = Color.White,
    inverseSurface = Color(0xFF1F242D), inverseOnSurface = Color(0xFFE6E9EF), inversePrimary = Palette.AccentDark,
)

private val WidgetDark = darkColorScheme(
    primary = Palette.AccentDark, onPrimary = Color(0xFF00205F),
    primaryContainer = Color(0xFF1D3A8A), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB7C0D0), onSecondary = Palette.Ink,
    secondaryContainer = Palette.SlateHigh, onSecondaryContainer = Color(0xFFE6E9EF),
    background = Palette.Ink, onBackground = Color(0xFFE6E9EF),
    surface = Palette.Graphite, onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Palette.Slate, onSurfaceVariant = Color(0xFFA9B2C1),
    outline = Color(0xFF3C4452), outlineVariant = Color(0xFF2A303B),
    error = Palette.ErrorDark, onError = Color(0xFF5C0F0F),
    tertiary = Color(0xFF5EDDD6), onTertiary = Color(0xFF00302E),
    inverseSurface = Color(0xFFE6E9EF), inverseOnSurface = Palette.Ink, inversePrimary = Palette.Accent,
)

private val LocalWidgetThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

/** Follows the in-app Light/Dark choice; "System" follows the launcher's day/night. */
@Composable
fun WidgetTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val colors = when (mode) {
        ThemeMode.SYSTEM -> ColorProviders(light = WidgetLight, dark = WidgetDark)
        ThemeMode.LIGHT -> ColorProviders(light = WidgetLight, dark = WidgetLight)
        ThemeMode.DARK -> ColorProviders(light = WidgetDark, dark = WidgetDark)
    }
    CompositionLocalProvider(LocalWidgetThemeMode provides mode) {
        GlanceTheme(colors = colors, content = content)
    }
}

/** A colour with separate light/dark values that honours the in-app theme override. */
@Composable
fun themed(day: Color, night: Color): ColorProvider = when (LocalWidgetThemeMode.current) {
    ThemeMode.SYSTEM -> ColorProvider(day = day, night = night)
    ThemeMode.LIGHT -> ColorProvider(day = day, night = day)
    ThemeMode.DARK -> ColorProvider(day = night, night = night)
}

object WidgetColors {
    val White = Color.White
    @Composable fun cool() = themed(Palette.Cool, Color(0xFF7FB0FF))
    @Composable fun heat() = themed(Palette.Heat, Color(0xFFFFA25C))
}
