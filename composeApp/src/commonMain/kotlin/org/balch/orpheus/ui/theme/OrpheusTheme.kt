package org.balch.orpheus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Synth-inspired color palette
private val NeonCyan = Color(0xFF00F5FF)
private val NeonMagenta = Color(0xFFFF00FF)
private val NeonOrange = Color(0xFFFF9F00) // Added Neon Orange
private val ElectricBlue = Color(0xFF0080FF)
private val DeepPurple = Color(0xFF1A0A2E)
private val DarkVoid = Color(0xFF0D0D1A)
private val SoftPurple = Color(0xFF2D1B4E)
private val WarmGlow = Color(0xFFFF6B35)
private val SynthGreen = Color(0xFF39FF14)
private val SynthPink = Color(0xFFFF69B4)
private val SeahawksNavy = Color(0xFF002244)
private val SeahawksGreen = Color(0xFF69BE28)
private val SeahawksGrey = Color(0xFFA5ACAF)
private val NinersRed = Color(0xFFAA0000)
private val NinersGold = Color(0xFFB3995D)

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DarkVoid,
    primaryContainer = SoftPurple,
    onPrimaryContainer = NeonCyan,
    secondary = NeonMagenta,
    onSecondary = DarkVoid,
    secondaryContainer = DeepPurple,
    onSecondaryContainer = NeonMagenta,
    tertiary = ElectricBlue,
    onTertiary = DarkVoid,
    tertiaryContainer = SoftPurple,
    onTertiaryContainer = ElectricBlue,
    error = WarmGlow,
    onError = DarkVoid,
    errorContainer = Color(0xFF4A1F1F),
    onErrorContainer = WarmGlow,
    background = DarkVoid,
    onBackground = Color(0xFFE0E0E0),
    surface = DeepPurple,
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = SoftPurple,
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF4A4A6A),
    outlineVariant = Color(0xFF2A2A4A),
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = DarkVoid,
    inversePrimary = DeepPurple,
    surfaceTint = NeonCyan,
)

// Light scheme for completeness (synth apps are typically dark)
private val LightColorScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = ElectricBlue,
    secondary = Color(0xFF8B008B),
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
)

@Composable
fun OrpheusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// Convenience extension colors for synth-specific UI
object OrpheusColors {
    val neonCyan = NeonCyan
    val neonMagenta = NeonMagenta
    val neonOrange = NeonOrange // Added
    val electricBlue = ElectricBlue
    val deepPurple = DeepPurple
    val darkVoid = DarkVoid
    val softPurple = SoftPurple
    val warmGlow = WarmGlow
    val synthGreen = SynthGreen
    val synthPink = SynthPink // Added
    val seahawksNavy = SeahawksNavy
    val seahawksGreen = SeahawksGreen
    val seahawksGrey = SeahawksGrey
    val ninersRed = NinersRed
    val ninersGold = NinersGold

    // Midnight Blue & Silver Palette
    val midnightBlue = Color(0xFF191970) // Deep Midnight Blue
    val deepSpaceBlue = Color(0xFF0F172A) // Darker, for backgrounds
    val sterlingSilver = Color(0xFFE2E8F0) // Bright Silver/White
    val slateSilver = Color(0xFF94A3B8)   // Muted Silver/Grey
    val metallicBlue = Color(0xFF60A5FA)  // Brighter, more vibrant blue accent

    // Glow colors for knobs/buttons
    val knobGlow = NeonCyan.copy(alpha = 0.6f)
    val pulseGlow = NeonMagenta.copy(alpha = 0.8f)
    val holdGlow = SynthGreen.copy(alpha = 0.7f)
    val fadedCyan = Color(0xFF00A0A0)
}
