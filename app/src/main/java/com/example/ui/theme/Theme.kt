package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SignalEmerald,
    onPrimary = ObsidianVoid,
    primaryContainer = DeepEmeraldContainer,
    onPrimaryContainer = SoftEmeraldTint,
    secondary = VoltAmber,
    onSecondary = ObsidianVoid,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = VoltAmber,
    tertiary = ElectricCyan,
    onTertiary = ObsidianVoid,
    tertiaryContainer = CyanContainerDark,
    onTertiaryContainer = ElectricCyan,
    error = SignalCoral,
    onError = Color.White,
    errorContainer = CoralContainerDark,
    onErrorContainer = SignalCoral,
    background = ObsidianVoid,
    onBackground = InkLightPrimary,
    surface = ObsidianSurface,
    onSurface = InkLightPrimary,
    surfaceVariant = ObsidianContainer,
    onSurfaceVariant = InkLightSecondary,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = ObsidianContainerLow,
    surfaceContainer = ObsidianContainer,
    surfaceContainerHigh = ObsidianContainerHigh,
    surfaceContainerHighest = ObsidianOutlineVariant,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = DeepEmerald,
    onPrimary = Color.White,
    primaryContainer = SoftEmeraldTint,
    onPrimaryContainer = DeepEmeraldContainer,
    secondary = DeepAmber,
    onSecondary = Color.White,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = DeepAmber,
    tertiary = DeepCyan,
    onTertiary = Color.White,
    tertiaryContainer = CyanContainerLight,
    onTertiaryContainer = DeepCyan,
    error = DeepCoral,
    onError = Color.White,
    errorContainer = CoralContainerLight,
    onErrorContainer = DeepCoral,
    background = AlabasterCanvas,
    onBackground = InkDarkPrimary,
    surface = PureSurface,
    onSurface = InkDarkPrimary,
    surfaceVariant = SlateContainer,
    onSurfaceVariant = InkDarkSecondary,
    surfaceContainerLowest = PureSurface,
    surfaceContainerLow = SlateContainerLow,
    surfaceContainer = SlateContainer,
    surfaceContainerHigh = SlateContainerHigh,
    surfaceContainerHighest = SlateOutline,
    outline = SlateOutline,
    outlineVariant = SlateOutlineVariant
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JobPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
