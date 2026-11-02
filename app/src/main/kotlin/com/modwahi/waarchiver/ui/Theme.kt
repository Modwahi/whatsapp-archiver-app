package com.modwahi.waarchiver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modwahi.waarchiver.R

val Plex = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B7A66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EFE4),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF486460),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE9E5),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF8A5100),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5F8F6),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF4F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3E9E7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CD9BE),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF89F6DA),
    secondary = Color(0xFFB0CCC6),
    secondaryContainer = Color(0xFF314C47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFFFB960),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDDB8),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF0A0F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.Bold),
    headlineMedium = headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.Bold),
    headlineSmall = headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleLarge = titleLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleMedium = titleMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    titleSmall = titleSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium),
    bodyLarge = bodyLarge.copy(fontFamily = f), bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
    labelMedium = labelMedium.copy(fontFamily = f), labelSmall = labelSmall.copy(fontFamily = f),
)

/** ألوان الحالة (نجاح/تحذير) غير الموجودة في Material */
@Immutable
data class StatusColors(val ok: Color, val okContainer: Color, val warn: Color, val warnContainer: Color)

val LocalStatus = staticCompositionLocalOf {
    StatusColors(Color(0xFF1E7B34), Color(0xFFD5F5DC), Color(0xFF8A5100), Color(0xFFFFDDB8))
}

@Composable
fun AppTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val status = if (dark) StatusColors(Color(0xFF7FDA93), Color(0xFF0F3A1B), Color(0xFFFFB960), Color(0xFF4A2A00))
    else StatusColors(Color(0xFF1E7B34), Color(0xFFD5F5DC), Color(0xFF8A5100), Color(0xFFFFE8CC))
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography().withFont(Plex),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status, content = content)
    }
}

val Mono = TextStyle(fontFamily = Plex, fontSize = 13.sp, lineHeight = 20.sp)
