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
private val NinersRed = Color(0xFFFF5252)
private val NinersGold = Color(0xFFFFD740)

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
    errorContainer = OrpheusColors.errorContainerDark,
    onErrorContainer = WarmGlow,
    background = DarkVoid,
    onBackground = OrpheusColors.onSurfaceDark,
    surface = DeepPurple,
    onSurface = OrpheusColors.onSurfaceDark,
    surfaceVariant = SoftPurple,
    onSurfaceVariant = OrpheusColors.onSurfaceVariantDark,
    outline = OrpheusColors.outlineDark,
    outlineVariant = OrpheusColors.outlineVariantDark,
    inverseSurface = OrpheusColors.onSurfaceDark,
    inverseOnSurface = DarkVoid,
    inversePrimary = DeepPurple,
    surfaceTint = NeonCyan,
)

// Light scheme for completeness (synth apps are typically dark)
private val LightColorScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = OrpheusColors.primaryContainerLight,
    onPrimaryContainer = ElectricBlue,
    secondary = OrpheusColors.secondaryLight,
    onSecondary = Color.White,
    background = OrpheusColors.backgroundLight,
    onBackground = OrpheusColors.onBackgroundLight,
    surface = Color.White,
    onSurface = OrpheusColors.onBackgroundLight,
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

    // Material Theme Defaults
    val errorContainerDark = Color(0xFF4A1F1F)
    val onSurfaceDark = Color(0xFFF0F0F0)
    val onSurfaceVariantDark = Color(0xFFDCDCDC)
    val outlineDark = Color(0xFF4A4A6A)
    val outlineVariantDark = Color(0xFF2A2A4A)

    val primaryContainerLight = Color(0xFFD0E4FF)
    val secondaryLight = Color(0xFF8B008B)
    val backgroundLight = Color(0xFFF5F5F5)
    val onBackgroundLight = Color(0xFF1A1A1A)

    // UI Panel & Widget Colors
    val panelBackground = Color(0xFF1E1E2E)
    val panelSurface = Color(0xFF32324A)
    val panelSurfaceDark = Color(0xFF1A1A2A)
    val charcoal = Color(0xFF1A1A1A)
    val darkGrey = Color(0xFF2A2A2A)
    val mediumGrey = Color(0xFF3A3A3A)
    val lightGrey = Color(0xFF555555)
    val greyShadow = Color(0xFF505050)
    val brightSilver = Color(0xFFE0E0E0)
    val steelGrey = Color(0xFFA0A0A0)
    val silverGrey = Color(0xFFAAAAAA)
    val slateGrey = Color(0xFF808080)
    val darkShadow = Color(0xFF111111)
    val mediumShadow = Color(0xFF222222)
    val lightShadow = Color(0xFF333333)
    val greyHighlight = Color(0xFF383838)
    val metallicHighlight = Color(0xFF505060)
    val metallicSurface = Color(0xFF404050)
    val metallicShadow = Color(0xFF353545)
    val metallicDark = Color(0xFF252530)
    val lightSteel = Color(0xFFD0D0D0)
    val deepCharcoal = Color(0xFF202020)
    val almostBlack = Color(0xFF101010)
    val pureWhite = Color(0xFFFFFFFF)

    // AI & Mode Colors
    val aiDrone = Color(0xFF4FC3F7)
    val aiSolo = Color(0xFFFFB74D)
    val aiRepl = Color(0xFF81C784)
    val aiChat = Color(0xFFBA68C8)

    // Section/Feature Colors
    val evoGold = Color(0xFFFDBB30)
    val presetOrange = Color(0xFFFFAB40)
    val vizGreen = Color(0xFF90EE90)
    val stereoCyan = Color(0xFF008B8B)
    val vizBackground = Color(0xFF151020)
    val neonCyanBright = Color(0xFF00E5FF)

    val grainsRed = Color(0xFF886767)
    val speechRose = Color(0xFFE88BA0)

    val patchOrange = Color(0xFFFF9500)
    val lfoBackground = Color(0xFF4A4A5A)
    val greyText = Color(0xFF888888)
    val tidalBackground = Color(0xFF1E1E2E)
    val galaxyBackground = Color(0xFF000008)
    val fireworksBackground = Color(0xFF050510)
    val blackHoleBackground = Color(0xFF0a0515)
    val deepSpaceDark = Color(0xFF0A0A12)
    val deepSpaceMid = Color(0xFF12121A)
    val deepSpaceGrey = Color(0xFF0D0D0D)

    // GalaxyViz Colors
    val galaxyCore = Color(0xFFFF6030)
    val galaxyMid = Color(0xFFAA40AA)
    val galaxyRim = Color(0xFF1B3984)
    val galaxyStarBlue = Color(0xFF80C0FF)
    val galaxyStarPink = Color(0xFFFF80C0)

    // SwirlyViz Colors
    val swirlyPurple = Color(0xFF6B4A8E)
    val swirlyBluePurple = Color(0xFF4A6B8E)
    val swirlyTealBlue = Color(0xFF3D7A8E)
    val swirlyMutedPurple = Color(0xFF5A5A8E)
    val swirlyTeal = Color(0xFF4A7A7A)
    val swirlyPinkPurple = Color(0xFF7A5A7A)
    val swirlyPinkBright = Color(0xFFAA5599)
    val swirlyLightBlue = Color(0xFF5599AA)

    // BlackHoleSunViz Colors
    val blackHoleDeep = Color(0xFF0a0515)
    val blackHoleVoid = Color(0xFF050210)
    val blackHoleEdge = Color(0xFF020108)
    val blackHoleDiskPurple = Color(0xFFaa4488)
    val blackHoleDiskOrange = Color(0xFFff6633)
    val blackHoleDiskGold = Color(0xFFffaa55)
    val blackHoleHorizonGold = Color(0xFFffcc88)
    val blackHoleHorizonOrange = Color(0xFFff8844)
    val blackHoleParticleOrange = Color(0xFFff8855)

    // Strategy Colors
    val strategyGreen = Color(0xFF4CAF50)
    val strategyBlue = Color(0xFF2196F3)
    val strategyMagenta = Color(0xFFE91E63)
    val strategyOrange = Color(0xFFFF9800)

    // Live Code / Tidal Colors
    val tidalNumber = Color(0xFFFFB86C)
    val tidalComment = Color(0xFF6272A4)
    val tidalBracket = Color(0xFFBD93F9)
    val tidalString = Color(0xFFF1FA8C)
    val tidalSilence = Color(0xFF8BE9FD)
    val tidalHighlight = Color(0xFF00FF88)

    // Sequencer Colors
    val seqRed = Color(0xFFFF6B6B)
    val seqGreen = Color(0xFF6BFF6B)
    val seqDarkRed = Color(0xFF3A2A2A)
    val seqDarkGreen = Color(0xFF2A3A2A)
    val seqDelayBlue = Color(0xFF4080FF)
    val seqDelayLightBlue = Color(0xFF6090FF)

    // Midnight Blue & Silver Palette
    val midnightBlue = Color(0xFF191970) // Deep Midnight Blue
    val deepSpaceBlue = Color(0xFF0F172A) // Darker, for backgrounds
    val sterlingSilver = Color(0xFFE2E8F0) // Bright Silver/White
    val slateSilver = Color(0xFF94A3B8)   // Muted Silver/Grey
    val metallicBlue = Color(0xFF60A5FA)  // Brighter, more vibrant blue accent
    val metallicBlueLight = Color(0xFF90CAF9) // Lighter version for Flux panel

    // Glow colors for knobs/buttons
    val knobGlow = NeonCyan.copy(alpha = 0.6f)
    val pulseGlow = NeonMagenta.copy(alpha = 0.8f)
    val holdGlow = SynthGreen.copy(alpha = 0.7f)
    val fadedCyan = Color(0xFF00A0A0)
    
    // LA Lakers Color Palette (for Resonator panel)
    val lakersPurple = Color(0xFF552583)        // Official Lakers Purple
    val lakersGold = Color(0xFFFDB927)          // Official Lakers Gold
    val lakersPurpleDark = Color(0xFF3A1A5C)    // Darker purple for backgrounds
    val lakersPurpleLight = Color(0xFF7B3FA0)   // Lighter purple for accents
    val lakersGoldBright = Color(0xFFFFD700)    // Brighter gold for highlights
    
    // Looper Fire Orange Color Palette
    val looperFireOrange = Color(0xFFFF6B2C)     // Primary fire orange
    val looperEmber = Color(0xFFFF8C42)          // Lighter ember orange
    val looperFlame = Color(0xFFFF4500)          // Intense flame red-orange
    val looperBrown = Color(0xFF8B4513)          // Saddle brown highlight
    val looperChestnut = Color(0xFFA0522D)       // Sienna/chestnut brown
    val looperBurnt = Color(0xFFCC5500)          // Burnt orange
    val looperAmber = Color(0xFFFFBF00)          // Warm amber glow
    val looperGreen = Color(0xFF00FF88)          // Vibrant green for play
    val looperCoal = Color(0xFF3D2B1F)           // Dark coal brown for backgrounds
    val looperAsh = Color(0xFF4A3728)            // Ash brown surface
    
    // Bass Voice Palette — warm amber, earthy, distinct from warps/echo
    val bassAmber = Color(0xFFFFAB40)            // Primary bass amber
    val bassDarkAmber = Color(0xFF4A3010)        // Dark amber shadow for knob tracks
    val bassKnobCap = Color(0xFFFFCC70)          // Lighter amber cap highlight

    // Horn (Leslie) Palette — blackout crimson, embers in darkness
    val hornCrimson = Color(0xFFCC2222)              // Primary horn accent (bright crimson)
    val hornWoofer = Color(0xFF881111)               // Secondary woofer accent (dark crimson)
    val hornBg = Color(0xFF080808)                   // Near-black background
    val hornBorder = Color(0xFF1A0808)               // Subtle crimson border
    val hornKnob = Color(0xFFAA2222)                 // Knob ring crimson

    // DJ Turntable Palette — Cleveland Guardians
    val djNavy = Color(0xFF0C2340)                   // Midnight Navy (panel bg, track)
    val djRed = Color(0xFFE31937)                    // Fastball Red (primary accent)
    val djRedLight = Color(0xFFFF4D5E)               // Lighter red (knob, progress)
    val djCream = Color(0xFFF5F0E1)                  // Cream (labels)
    val djIceBlue = Color(0xFF4FC3F7)                // Ice blue (frozen indicator)

    // Drop zone accents (DJ Drops feature)
    val dropFilter  = djIceBlue                 // FILTER — ice blue
    val dropBrake   = Color(0xFFFF2530)         // BRAKE — vivid red (stop signal)
    val dropStutter = Color(0xFF38E03A)         // STUTTER — acid green
    val dropFreeze  = Color(0xFFE34CC9)         // FREEZE — magenta
    val dropOctave  = Color(0xFF7D5CFF)         // OCTAVE — deep violet sub-bass
    val dropPhaser  = Color(0xFF2FE0D5)         // PHASER — teal swirl
    val dropEcho    = Color(0xFFFFC14D)         // ECHO — warm amber repeats
    val dropRing    = Color(0xFFB8F52A)         // RING — toxic yellow-green metallic

    // Timer (Sleep) Palette — legacy amber
    val timerAmber = Color(0xFFFFB050)
    val timerDarkAmber = Color(0xFF3A2510)
    val timerGlowAmber = Color(0xFFFFCC70)
    val timerEmber = Color(0xFFCC6620)

    // Sleep Timer Palette — moonlight blue/indigo for nighttime calm
    val sleepMoonlight = Color(0xFF8BB8D4)          // Soft moonlight blue — primary accent
    val sleepIndigo = Color(0xFF151030)              // Deep night indigo — card backgrounds
    val sleepGlow = Color(0xFFA8D0F0)                // Pale blue glow — highlights
    val sleepDusk = Color(0xFF4A5A8A)                // Twilight blue-grey — mid-tone
    val sleepEmber = Color(0xFF6B78AA)               // Fading dusk — low-urgency state

    // Warps Meta-Modulator Palette
    val warpsGreen = Color(0xFF90EE90)           // Primary Warps Green
    val warpsYellow = Color(0xFFFFFF00)          // Bright Yellow for accents
    val warpsDarkGreen = Color(0xFF2E4D2E)       // Darker green for backgrounds

    // Echo/Reverb Palette
    val echoLavender = Color(0xFFB39DDB)           // Soft lavender — ethereal reverb trails
    val echoPeriwinkle = Color(0xFF9FA8DA)         // Cool periwinkle — spacious depth

    // Engine Picker Colors
    val enginePurple = Color(0xFFBA68C8)         // Waveshaping
    val engineRed = Color(0xFFE57373)            // Virtual Analog
    val engineBlue = Color(0xFF64B5F6)           // Additive
    val engineGreen = Color(0xFF81C784)          // Grain
    val engineYellow = Color(0xFFFFD54F)         // String
    val engineOrange = Color(0xFFFF8A65)         // Modal

    // Aquarium colors
    val aquariumDeep = Color(0xFF0A1628)       // Deep ocean background
    val aquariumTeal = Color(0xFF0E4D64)       // Teal mid-water
    val aquariumLight = Color(0xFF1A7A8A)      // Light shaft color
    val aquariumBubble = Color(0x40FFFFFF)     // Translucent bubble
    val fishOrange = Color(0xFFFF8C42)         // Clownfish orange
    val fishBlue = Color(0xFF4A90D9)           // Blue tang
    val fishSilver = Color(0xFFC0C8D0)        // Silver minnow
    val fishRed = Color(0xFFE04040)            // Red snapper
    val fishGold = Color(0xFFFFD700)           // Goldfish
    val fishTeal = Color(0xFF40C4AA)           // Teal angel
    val fishPurple = Color(0xFF9B6DFF)         // Purple reef fish
    val fishPink = Color(0xFFFF6B9D)           // Pink chromis

    // Mt. Hood Viz Colors (from photo reference)
    val hoodSkyTop = Color(0xFF4A5566)           // Darker steel gray-blue upper sky
    val hoodSkyMid = Color(0xFFB07848)           // Darker peach/amber mid sky
    val hoodHorizon = Color(0xFFA05028)          // Deeper orange at horizon
    val hoodTwilightTop = Color(0xFF1A1040)      // Indigo twilight upper
    val hoodTwilightMid = Color(0xFF8B2252)      // Magenta twilight mid
    val hoodTwilightHorizon = Color(0xFF8B1A1A)  // Deep crimson twilight horizon
    val hoodMountain = Color(0xFF1A1A1A)         // Near-black mountain silhouette
    val hoodForeground = Color(0xFF0A0A0A)       // Pure dark foreground ridge
    val hoodBackRidge = Color(0xFF252525)        // Slightly lighter back ridge
    val hoodLavaOrange = Color(0xFFFF6B00)       // Hot lava orange
    val hoodLavaRed = Color(0xFFFF2200)          // Lava red glow
    val hoodLavaYellow = Color(0xFFFFCC00)       // Lava bright yellow center
    val hoodRimGlow = Color(0xFFFF8844)          // Orange rim glow on silhouette edge
    val hoodSmoke = Color(0xFF888888)            // Smoke/ash gray
    val hoodBackground = Color(0xFF1A1520)       // Default background

    // Ant Colony Viz Colors
    val antColonyBackground = Color(0xFF0A0515)       // Deep purple-black
    val antColonyGround = Color(0xFF1A0A2E)           // Dark purple ground
    val antColonyGridLine = Color(0xFF2A1A3E)         // Faint purple grid lines
    val antNeonCyan = Color(0xFF00F5FF)               // Ant trail cyan
    val antNeonMagenta = Color(0xFFFF00FF)            // Ant trail magenta
    val antNeonGreen = Color(0xFF39FF14)              // Ant trail green
    val antNeonOrange = Color(0xFFFF9F00)             // Block edge orange
    val antNeonPink = Color(0xFFFF69B4)               // Block edge pink
    val antNeonBlue = Color(0xFF4488FF)               // Duo 5 blue
    val antNeonYellow = Color(0xFFFFE033)             // Duo 6 yellow
    val antFireRed = Color(0xFFFF2200)                // Fire ant red
    val antFireGlow = Color(0xFFFF4400)               // Fire ant glow
    val antMagGlassWhite = Color(0xFFFFFFEE)          // Magnifying glass focal point
    val antMagGlassBurn = Color(0xFFFF6600)           // Burn orange
    val antBootNeon = Color(0xFF00E5FF)               // Neon boot outline
    val antEmberYellow = Color(0xFFFFCC00)            // Ember particles

    // Pulsar Beat Machine
    val cosmicPurple = Color(0xFF7C4DFF)

    fun accentFor(drop: org.balch.orpheus.core.plugin.symbols.DjDrop): Color = when (drop) {
        org.balch.orpheus.core.plugin.symbols.DjDrop.NONE    -> Color.Transparent
        org.balch.orpheus.core.plugin.symbols.DjDrop.FILTER  -> dropFilter
        org.balch.orpheus.core.plugin.symbols.DjDrop.BRAKE   -> dropBrake
        org.balch.orpheus.core.plugin.symbols.DjDrop.STUTTER -> dropStutter
        org.balch.orpheus.core.plugin.symbols.DjDrop.FREEZE  -> dropFreeze
        org.balch.orpheus.core.plugin.symbols.DjDrop.OCTAVE  -> dropOctave
        org.balch.orpheus.core.plugin.symbols.DjDrop.PHASER  -> dropPhaser
        org.balch.orpheus.core.plugin.symbols.DjDrop.ECHO    -> dropEcho
        org.balch.orpheus.core.plugin.symbols.DjDrop.RING    -> dropRing
    }
}
