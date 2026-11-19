package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = OnCyanContainer,
    secondary = CobaltSignal,
    onSecondary = OnCobaltSignal,
    secondaryContainer = CobaltContainer,
    onSecondaryContainer = OnCobaltContainer,
    tertiary = VioletAccent,
    onTertiary = OnVioletAccent,
    tertiaryContainer = VioletContainer,
    onTertiaryContainer = OnVioletContainer,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateContainer,
    surfaceContainerHigh = SlateContainerHigh,
    outline = SlateOutline,
    outlineVariant = Color(0xFF1E293B),
    error = CrimsonStop,
    onError = OnCrimson
)

private val StudioLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = LightTertiary,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightContainer,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = CrimsonStop,
    onError = Color.White
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AirCastBridgeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = StudioShapes,
        content = content
    )
}
