package org.balch.orpheus.djapp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * An endless song (past its forced end with nothing armed) holds its arc short of full. The gap
 * left blazes instead of sitting dim, so the ring reads as still going, not stuck: a breathing glow
 * with glints twinkling along it, on the ring dancing on past 12 over the arc's start. Everything is a function of the play clock, so a pause freezes it
 * and a frame allocates nothing.
 */

/** Glints along the gap, each on its own staggered twinkle. */
internal const val GapSparks = 6

/** One glint's life, dark to its flash and back, after which it reappears somewhere else. */
internal const val SparkTwinkleMs = 900L

/** Glints keep this share of their stretch clear at each end, so none sits half off it. */
internal const val SparkMargin = 0.04f

/**
 * On the ring the glints dance from 11 to 1 o'clock (canvas degrees, 12 o'clock at -90): across the
 * gap's end and over the arc's start, as if the song wraps round and carries on.
 */
private const val DanceFromDeg = -120f
private const val DanceSweepDeg = 60f

/**
 * Against the gap's line: a wide faint bloom, a brighter glow as wide as the ring's band (box edge to
 * the dome's clearance), and a thin white-hot filament down the gap, so it blazes yet still reads as
 * unfilled.
 */
internal const val BloomWidthShare = 3f
internal const val GlowWidthShare = 2f
internal const val FilamentWidthShare = 0.35f

/** The glow's slow breath, and how hot it all burns: the filament the music's colour almost to white. */
private const val BreatheMs = 1_600f
private const val FilamentLighten = 0.85f
private const val BloomAlpha = 0.16f
private const val GlowAlpha = 0.4f
private const val FilamentAlpha = 1f

/** A ring glint's arm against the ring's stroke, and a band glint's against the band's track. */
private const val RingSparkArmShare = 1.25f
private const val BandSparkArmShare = 2.3f

private val TwoPi = (2 * PI).toFloat()
private const val DegToRad = (PI / 180).toFloat()

/** The glow's strength at [playMs], breathing between 0.6 and 1. */
internal fun gapBreath(playMs: Long): Float = 0.8f + 0.2f * sin(TwoPi * playMs / BreatheMs)

/**
 * A glint's brightness over one twinkle, [phase] 0..1. Dark at both ends, so its jump to a new
 * spot between twinkles never shows.
 */
internal fun sparkTwinkle(phase: Float): Float {
    // A soft swell: sin cubed lingers dark and flashes briefly at the peak.
    val s = sin(PI.toFloat() * phase.coerceIn(0f, 1f))
    return s * s * s
}

/** Calls [spark] with each glint's place along its stretch (0 at the start, 1 at the end) and its brightness now. */
internal inline fun forEachGapSpark(playMs: Long, spark: (along: Float, brightness: Float) -> Unit) {
    for (i in 0 until GapSparks) {
        val t = playMs + i * SparkTwinkleMs / GapSparks
        val twinkle = t / SparkTwinkleMs
        val phase = (t % SparkTwinkleMs).toFloat() / SparkTwinkleMs
        spark(SparkMargin + (1f - 2f * SparkMargin) * sparkSpot(i, twinkle), sparkTwinkle(phase))
    }
}

/** Where glint [i] shows during its [twinkle]th flash, 0..1: SplitMix64, so it scatters but never flickers between frames. */
internal fun sparkSpot(i: Int, twinkle: Long): Float {
    var z = twinkle * GapSparks + i - 0x61C8864680B583EBL
    z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
    z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
    z = z xor (z ushr 31)
    return (z ushr 40).toFloat() / (1 shl 24)
}

/**
 * An eight-point glint at [at]: a white-hot cross with shorter diagonals and a hot centre, in a
 * [halo] of the music's colour, sized and lit by [brightness].
 */
private fun DrawScope.drawGlint(at: Offset, arm: Float, halo: Color, brightness: Float) {
    if (brightness <= 0f) return
    val a = arm * (0.5f + 0.5f * brightness)
    drawCircle(halo.copy(alpha = 0.7f * brightness), a * 0.85f, at)
    val core = Color.White.copy(alpha = brightness)
    val width = a * 0.22f
    drawLine(core, Offset(at.x - a, at.y), Offset(at.x + a, at.y), width, StrokeCap.Round)
    drawLine(core, Offset(at.x, at.y - a), Offset(at.x, at.y + a), width, StrokeCap.Round)
    val d = a * 0.4f
    drawLine(core, Offset(at.x - d, at.y - d), Offset(at.x + d, at.y + d), width * 0.7f, StrokeCap.Round)
    drawLine(core, Offset(at.x - d, at.y + d), Offset(at.x + d, at.y - d), width * 0.7f, StrokeCap.Round)
    drawCircle(core, width * 0.9f, at)
}

/**
 * Under the ring's elapsed arc: a bloom and glow from the arc's end at [progress] (or 11 o'clock,
 * where the glints start) round to 1 o'clock, and the white-hot filament along the gap alone. The
 * arc drawn over it keeps its colour, haloed where the glow runs past 12.
 */
internal fun DrawScope.drawEndlessRingGlow(progress: Float, radius: Float, color: Color, strokes: RingStrokes, playMs: Long) {
    val p = progress.coerceIn(0f, 1f)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val start = -90f + 360f * p
    val glowFrom = min(start, DanceFromDeg + 360f)
    val glowSweep = DanceFromDeg + DanceSweepDeg + 360f - glowFrom
    val breath = gapBreath(playMs)
    drawArc(color.copy(alpha = BloomAlpha * breath), glowFrom, glowSweep, false, topLeft, arcSize, style = strokes.bloom)
    drawArc(color.copy(alpha = GlowAlpha * breath), glowFrom, glowSweep, false, topLeft, arcSize, style = strokes.glow)
    val hot = color.lighten(FilamentLighten)
    drawArc(hot.copy(alpha = FilamentAlpha * breath), start, 360f * (1f - p), false, topLeft, arcSize, style = strokes.filament)
}

/** Over the ring's elapsed arc: the glints, dancing from 11 to 1 o'clock (see [DanceFromDeg]). */
internal fun DrawScope.drawEndlessRingGlints(radius: Float, color: Color, strokes: RingStrokes, playMs: Long) {
    val arm = strokes.width * RingSparkArmShare
    forEachGapSpark(playMs) { along, brightness ->
        val angle = (DanceFromDeg + DanceSweepDeg * along) * DegToRad
        drawGlint(Offset(center.x + radius * cos(angle), center.y + radius * sin(angle)), arm, color, brightness)
    }
}

/** The band's gap, from the playhead at [playX] to its right edge along [baseline], blazing as the ring's does. */
internal fun DrawScope.drawEndlessBandGap(playX: Float, width: Float, baseline: Float, track: Float, color: Color, playMs: Long) {
    val breath = gapBreath(playMs)
    val hot = color.lighten(FilamentLighten)
    val from = Offset(playX, baseline)
    val to = Offset(width, baseline)
    drawLine(color.copy(alpha = BloomAlpha * breath), from, to, track * BloomWidthShare, StrokeCap.Round)
    drawLine(color.copy(alpha = GlowAlpha * breath), from, to, track * GlowWidthShare, StrokeCap.Round)
    // The band's track is already thin: the ring's share of it would vanish.
    drawLine(hot.copy(alpha = FilamentAlpha * breath), from, to, track * 0.6f, StrokeCap.Round)
    val arm = track * BandSparkArmShare
    forEachGapSpark(playMs) { along, brightness ->
        drawGlint(Offset(playX + (width - playX) * along, baseline), arm, color, brightness)
    }
}
