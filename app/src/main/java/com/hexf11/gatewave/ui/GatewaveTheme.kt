package com.hexf11.gatewave.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.hexf11.gatewave.ThemeMode

val PixelBlue = Color(0xFF0B63E5)
val HealthyGreen = Color(0xFF238A3B)
val WarmCanvas = Color(0xFFFAF9F6)
val Ink = Color(0xFF121416)

private val LightColors = lightColorScheme(
    primary = PixelBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF06285B),
    secondary = Color(0xFF4E5E78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EBF3),
    onSecondaryContainer = Color(0xFF171F2D),
    tertiary = HealthyGreen,
    onTertiary = Color.White,
    background = WarmCanvas,
    onBackground = Ink,
    surface = Color(0xFFFFFEFB),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0EFEC),
    onSurfaceVariant = Color(0xFF595B60),
    outline = Color(0xFFD4D4D1),
    outlineVariant = Color(0xFFE7E5E1),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003065),
    primaryContainer = Color(0xFF174A91),
    onPrimaryContainer = Color(0xFFD8E5FF),
    tertiary = Color(0xFF83D38F),
    background = Color(0xFF101113),
    onBackground = Color(0xFFF1F1EE),
    surface = Color(0xFF161719),
    onSurface = Color(0xFFF1F1EE),
    surfaceVariant = Color(0xFF242629),
    onSurfaceVariant = Color(0xFFC6C6C2),
    outline = Color(0xFF44464B),
    outlineVariant = Color(0xFF2D2F33),
    error = Color(0xFFFFB4AB),
)

private val PixelTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 47.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

private val PixelShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun GatewaveTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PixelTypography,
        shapes = PixelShapes,
        content = content,
    )
}
