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

// Cleveland Cavaliers Palette (Wine & Gold)
private val CavsWine = Color(0xFF860038)
private val CavsGold = Color(0xFFFFBB30)
private val CavsNavy = Color(0xFF041E42)
private val CavsWhite = Color(0xFFFFFFFF)

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

    // Midnight Blue & Silver Palette
    val midnightBlue = Color(0xFF191970) // Deep Midnight Blue
    val deepSpaceBlue = Color(0xFF0F172A) // Darker, for backgrounds
    val sterlingSilver = Color(0xFFE2E8F0) // Bright Silver/White
    val slateSilver = Color(0xFF94A3B8)   // Muted Silver/Grey
    val metallicBlue = Color(0xFF3B82F6)  // Vibrant Blue Accent

    // Cavaliers Palette (Kept for reference if needed, but unused)
    val cavsWine = CavsWine
    val cavsGold = CavsGold
    val cavsNavy = CavsNavy
    val cavsWhite = CavsWhite

    // Backward-compatible aliases (map Browns to Midnight & Silver)
    val brownsBrown = midnightBlue    // Base background color
    val brownsOrange = metallicBlue   // Vibrant accent (was Orange/Gold, now Electric Blue)
    val brownsWhite = sterlingSilver  // Text color

    // Glow colors for knobs/buttons
    val knobGlow = NeonCyan.copy(alpha = 0.6f)
    val pulseGlow = NeonMagenta.copy(alpha = 0.8f)
    val holdGlow = SynthGreen.copy(alpha = 0.7f)
}

/**
 * Liquid glassmorphism effect constants - tweak these for easy adjustments.
 * Adjusted to let visualization colors shine through better.
 */
object LiquidEffects {
    // Frost blur amount (in dp) - reduced to let colors through
    const val FROST_SMALL = 5f      // For smaller panels
    const val FROST_MEDIUM = 7f     // Default
    const val FROST_LARGE = 9f      // For larger panels
    
    // Tint overlay alpha (0-1) - reduced for more transparency
    const val TINT_ALPHA = 0.12f
    
    // Saturation adjustment (<1 = desaturate, >1 = saturate)
    // Increased to let background colors pop more
    const val SATURATION = 0.65f
    
    // Contrast adjustment (<1 = reduce contrast/brightness)
    const val CONTRAST = 0.75f
    
    // Refraction and curve (0 = disabled for cleaner look)
    const val REFRACTION = 0f
    const val CURVE = 0f
}
