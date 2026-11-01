package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The factor the host folded into [androidx.compose.ui.platform.LocalDensity] to widen the dp
 * canvas (see [largeScreenDensityScale]); 1f where the density is native. A size in sp shrinks
 * by this factor on screen, so text that must stay readable divides it back out via [legible].
 */
val LocalCanvasScale: ProvidableCompositionLocal<Float> = compositionLocalOf { 1f }

private const val MinCanvasScale = 0.5f

/** [nativeSp] as the sp that renders at that size on a native-density screen. */
fun legibleSp(nativeSp: Float, canvasScale: Float): TextUnit =
    (nativeSp / canvasScale.coerceIn(MinCanvasScale, 1f)).sp

/** [legibleSp] for the current [LocalCanvasScale]. */
@Composable
fun legible(nativeSp: Float): TextUnit = legibleSp(nativeSp, LocalCanvasScale.current)

/** Native-density sizes, in sp, for text that must stay readable on a scaled canvas. */
object LegibleText {
    const val CHAT_BODY = 15f
    const val SECONDARY = 13f
    const val MIXER_MIN = 11f
    const val MIXER_VALUE = 13f
    const val TITLE = 18f
}
