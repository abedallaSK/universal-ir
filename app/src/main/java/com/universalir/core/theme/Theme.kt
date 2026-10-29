package com.universalir.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.universalir.data.repository.ButtonSize
import com.universalir.data.repository.ThemeMode

/** Colour tokens: neutral surfaces, one accent, explicit success/warning/error. */
object Palette {
    val Accent = Color(0xFF2F6BFF)
    val AccentDark = Color(0xFF8AA8FF)
    val Ink = Color(0xFF0E1116)
    val Graphite = Color(0xFF161A21)
    val Slate = Color(0xFF1F242D)
    val SlateHigh = Color(0xFF2A303B)
    val Mist = Color(0xFFF6F7F9)
    val Cloud = Color(0xFFFFFFFF)
    val Fog = Color(0xFFE9ECF1)
    val Success = Color(0xFF1E9E61)
    val SuccessDark = Color(0xFF5BD69A)
    val Warning = Color(0xFFB7791F)
    val WarningDark = Color(0xFFF2C14E)
    val Error = Color(0xFFD93A3A)
    val ErrorDark = Color(0xFFFF8A80)
    val Cool = Color(0xFF3B82F6)
    val Heat = Color(0xFFF97316)
}

@Immutable
data class StatusColors(val success: Color, val warning: Color, val error: Color, val cool: Color, val heat: Color)

@Immutable
data class Spacing(val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp, val l: Dp = 16.dp, val xl: Dp = 24.dp, val xxl: Dp = 32.dp)

@Immutable
data class RemoteMetrics(val button: Dp, val padSize: Dp, val iconSize: Dp)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Palette.Success, Palette.Warning, Palette.Error, Palette.Cool, Palette.Heat) }
val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalRemoteMetrics = staticCompositionLocalOf { RemoteMetrics(64.dp, 220.dp, 26.dp) }

private val LightColors: ColorScheme = lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0A2A80),
    secondary = Color(0xFF4A5468),
    onSecondary = Color.White,
    secondaryContainer = Palette.Fog,
    onSecondaryContainer = Palette.Ink,
    background = Palette.Mist,
    onBackground = Palette.Ink,
    surface = Palette.Cloud,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Fog,
    onSurfaceVariant = Color(0xFF4A5468),
    surfaceTint = Palette.Accent,
    surfaceBright = Palette.Cloud,
    surfaceDim = Color(0xFFE2E6EC),
    surfaceContainerLowest = Palette.Cloud,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Palette.Cloud,
    surfaceContainerHigh = Color(0xFFF0F2F5),
    surfaceContainerHighest = Palette.Fog,
    inverseSurface = Color(0xFF1F242D),
    inverseOnSurface = Color(0xFFE6E9EF),
    inversePrimary = Palette.AccentDark,
    tertiary = Color(0xFF0E9F9A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDF3F0),
    onTertiaryContainer = Color(0xFF00403D),
    outline = Color(0xFFC5CBD5),
    outlineVariant = Color(0xFFE1E5EB),
    error = Palette.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E2),
    onErrorContainer = Color(0xFF5C0F0F),
    scrim = Color.Black,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.AccentDark,
    onPrimary = Color(0xFF00205F),
    primaryContainer = Color(0xFF1D3A8A),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB7C0D0),
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.SlateHigh,
    onSecondaryContainer = Color(0xFFE6E9EF),
    background = Palette.Ink,
    onBackground = Color(0xFFE6E9EF),
    surface = Palette.Graphite,
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Palette.Slate,
    onSurfaceVariant = Color(0xFFA9B2C1),
    surfaceTint = Palette.AccentDark,
    surfaceBright = Palette.SlateHigh,
    surfaceDim = Palette.Ink,
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF13171D),
    surfaceContainer = Palette.Graphite,
    surfaceContainerHigh = Palette.Slate,
    surfaceContainerHighest = Palette.SlateHigh,
    inverseSurface = Color(0xFFE6E9EF),
    inverseOnSurface = Palette.Ink,
    inversePrimary = Palette.Accent,
    tertiary = Color(0xFF5EDDD6),
    onTertiary = Color(0xFF00302E),
    tertiaryContainer = Color(0xFF0B4F4B),
    onTertiaryContainer = Color(0xFFCDF3F0),
    outline = Color(0xFF3C4452),
    outlineVariant = Color(0xFF2A303B),
    error = Palette.ErrorDark,
    onError = Color(0xFF5C0F0F),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Resolves the user's theme choice against the system setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun UniversalIrTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    buttonSize: ButtonSize = ButtonSize.MEDIUM,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.isDark()
    val status = if (dark) StatusColors(Palette.SuccessDark, Palette.WarningDark, Palette.ErrorDark, Color(0xFF7FB0FF), Color(0xFFFFA25C))
    else StatusColors(Palette.Success, Palette.Warning, Palette.Error, Palette.Cool, Palette.Heat)
    val metrics = when (buttonSize) {
        ButtonSize.SMALL -> RemoteMetrics(56.dp, 196.dp, 22.dp)
        ButtonSize.MEDIUM -> RemoteMetrics(64.dp, 220.dp, 26.dp)
        ButtonSize.LARGE -> RemoteMetrics(76.dp, 252.dp, 30.dp)
    }
    CompositionLocalProvider(LocalStatusColors provides status, LocalSpacing provides Spacing(), LocalRemoteMetrics provides metrics) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
