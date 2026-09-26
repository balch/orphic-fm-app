package org.balch.orpheus.djapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.core.plugin.viz.ScopeFrame
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarTrackColors
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val TwoPi = (2 * PI).toFloat()

/** One wavelength of travel per period until the tempo is measured (previews, a song's first steps). */
private const val WavePeriodMillis = 1200

/** How long the wave takes to rise to full height from flat (opened paused, then played). */
private const val WaveRiseMillis = 400

/** How hard the travel is pulled onto the beat: most of an error closes in about this long, never in a jump. */
private const val BeatLockMillis = 80f

/** The ring's track stroke, and the band its trace swings in, as shares of its size, so the 56-64dp rings never read thin. */
private const val RingStrokeShare = 1f / 16
private const val RingAmplitudeShare = 1f / 32

/**
 * The scope trace is a finer line than the track and swings further, in the same band: its outer
 * edge meets the ring box and its inner edge the dome's clearance.
 */
private const val ScopeStrokeShare = 1f / 26
private const val ScopeAmplitudeShare = RingAmplitudeShare + (RingStrokeShare - ScopeStrokeShare) / 2

/** Path points per dp of elapsed arc, enough that a curve never shows its corners. */
private const val ScopePointsPerDp = 1f

/**
 * Each point averages the window over this much arc, or this share of the window where that is
 * wider (a big ring stretches the window): hats and bright partials blur, and the path never zig-zags.
 */
private val ScopeBlur = 4.dp
private const val ScopeMinBlurShare = 1f / 32

/** The trace eases onto the track over this share of the ring's size at each end: 12 o'clock and the playhead. */
private const val ScopeTaperShare = 1f / 16

/** An elapsed arc shorter than this share of the ring's size is the plain arc; the trace grows to full height by the second. */
private const val ScopeMinArcShare = 3f / 16
private const val ScopeFullArcShare = 1f / 2

/** Ring box edge to its innermost crest: the stroke, the radius pull-in for the wave, and a crest's inward swing. */
internal fun ringInnerClearance(ringSize: Dp): Dp = ringSize * (RingStrokeShare + RingAmplitudeShare * 2)

/** The song band's playhead dot. */
internal val PlayheadRadius = 3.dp

/** A played stretch with nothing recorded, between the unplayed track's 0.2 and the story's full colour. */
internal const val UnheardAlpha = 0.5f

/** The zip lights the path's own colour this far toward white. */
internal const val ZipLighten = 0.5f
internal const val ZipAlpha = 0.9f
private const val ZipStops = 9

/** An elapsed path shorter than this has nothing to zip. */
internal val ZipMinLength = 4.dp

private const val NoZip = -1L

/**
 * Full [amplitude] while playing off TV hardware. Paused, the wave keeps its last shape, so it
 * stays at [held], its height when the pause landed. TV hardware always lies flat.
 */
internal fun waveAmplitudeTarget(playing: Boolean, televisionHardware: Boolean, held: Float, amplitude: Float = 1f): Float =
    when {
        televisionHardware -> 0f
        playing -> amplitude
        else -> held
    }

/** The length of the ring's elapsed arc at [progress], for gating the zip in composition. */
internal fun ringElapsedLength(ringSize: Dp, progress: Float): Dp =
    (ringSize * (0.5f - RingStrokeShare / 2 - RingAmplitudeShare)) * (TwoPi * progress.coerceIn(0f, 1f))

/**
 * One frame of the travel, in wavelengths: on at the tempo ([msPerBeat], or one per 1.2 s while
 * unknown) and, given the [beat]'s own phase (NaN when there is none), pulled onto it without ever jumping.
 */
internal fun followBeat(displayed: Float, dtMs: Float, beat: Float, msPerBeat: Float): Float {
    val dt = dtMs.coerceAtLeast(0f)
    val expected = displayed + dt / (if (msPerBeat > 0f) msPerBeat else WavePeriodMillis.toFloat())
    if (beat.isNaN() || msPerBeat <= 0f) return expected.mod(1f)
    val error = (beat - expected + 0.5f).mod(1f) - 0.5f
    return (expected + error * (1f - exp(-dt / BeatLockMillis))).mod(1f)
}

// The scope window along the elapsed arc, oldest at 12 o'clock and newest at the playhead, a
// point every [step] px, each the window's mean over [blur] px of arc (or ScopeMinBlurShare of
// the window, if wider). Both ends ease onto the track over [taper] px.
private inline fun forEachScopeArcPoint(
    center: Offset,
    radius: Float,
    sweepFraction: Float,
    amplitude: Float,
    trace: ScopeTrace,
    step: Float,
    blur: Float,
    taper: Float,
    action: (x: Float, y: Float) -> Unit,
) {
    val sweep = sweepFraction.coerceIn(0f, 1f) * TwoPi
    val length = radius * sweep
    val n = ceil(length / step.coerceAtLeast(0.1f)).toInt().coerceAtLeast(2)
    val points = trace.size.toFloat()
    val half = max(blur * points / length.coerceAtLeast(0.1f), points * ScopeMinBlurShare) / 2
    for (i in 0..n) {
        val u = i.toFloat() / n
        val envelope = (min(u, 1f - u) * length / taper.coerceAtLeast(0.1f)).coerceIn(0f, 1f)
        val at = u * points
        val r = radius + amplitude * envelope * trace.average(at - half, at + half).coerceIn(-1f, 1f)
        val theta = sweep * u
        action(center.x + r * sin(theta), center.y - r * cos(theta))
    }
}

// The elapsed arc as the trace, clockwise from 12 o'clock, built a quarter turn clockwise for a
// canvas turned a quarter back (see drawProgressRing).
private fun Path.addQuarterTurnedScopeArc(
    center: Offset, radius: Float, sweepFraction: Float, amplitude: Float, trace: ScopeTrace, step: Float, blur: Float, taper: Float,
) {
    var first = true
    forEachScopeArcPoint(center, radius, sweepFraction, amplitude, trace, step, blur, taper) { x, y ->
        val turnedX = center.x - (y - center.y)
        val turnedY = center.y + (x - center.x)
        if (first) moveTo(turnedX, turnedY) else lineTo(turnedX, turnedY)
        first = false
    }
}

/** The ring's centreline radius in a box [sizePx] across: pulled in by the stroke and the full swing. */
internal fun ringCentreRadius(sizePx: Float): Float = (sizePx - sizePx * RingStrokeShare) / 2 - sizePx * RingAmplitudeShare

/** The scope trace's greatest swing from the centreline, in a box [sizePx] across. */
internal fun scopeAmplitude(sizePx: Float): Float = sizePx * ScopeAmplitudeShare

/** How much of the trace's height an elapsed arc [arcShare] of the ring's size long shows: none when short, all once long. */
internal fun scopeArcFade(arcShare: Float): Float =
    ((arcShare - ScopeMinArcShare) / (ScopeFullArcShare - ScopeMinArcShare)).coerceIn(0f, 1f)

private class FixedState(override val value: Float) : State<Float>

/**
 * A progress wave's moving parts, read only in draw: [phase] (radians), [amplitude] (1 once it
 * has played, held through a pause), the smoothed live [level] and per-track [trackLevels]
 * (written just before [level] each frame), [zipMs], how far into the paused zip it is (-1 when
 * off), and [playMs], a clock that runs only while the wave travels. [clocked] is false where it
 * never runs: TV hardware and the render harness.
 */
@Stable
internal class ProgressWave(
    private val phaseState: FloatState,
    private val amplitudeState: State<Float>,
    private val levelState: FloatState,
    private val zipState: LongState,
    private val clockState: LongState,
    val trackLevels: FloatArray,
    val clocked: Boolean,
    private val scopeTrace: ScopeTrace? = null,
    private val scopeTickState: IntState? = null,
) {
    val phase: Float get() = phaseState.floatValue
    val amplitude: Float get() = amplitudeState.value
    val level: Float get() = levelState.floatValue
    val zipMs: Long get() = zipState.longValue
    val playMs: Long get() = clockState.longValue

    /**
     * The scope trace, null where there is no feed (the ring then draws its plain arc). Read in draw, it redraws with each new trace.
     * An engine whose scope never produces a window leaves it empty, so the ring draws flat.
     */
    val scope: ScopeTrace? get() = scopeTrace?.also { scopeTickState?.intValue }
}

/**
 * The music pulse for a live wave's frame loop, which reads it by value each frame. It is never
 * collected, so the viz-rate stream costs no dispatch or snapshot write; this holds it live, for
 * the recorder builds it only while held. Held only while started, as the scope feed is.
 */
@Composable
internal fun rememberMusicPulse(pulsarFeature: PulsarFeature): () -> MusicPulse {
    HoldWhileStarted(pulsarFeature) { pulsarFeature.holdMusicPulse() }
    return remember(pulsarFeature) { { pulsarFeature.musicPulseFlow.value } }
}

/**
 * Takes [hold] while the app is started, and gives it back when the app stops (an iOS app sent to
 * the background, a minimised desktop window) or this leaves the composition: LifecycleStartEffect's
 * rule, needing no frame. It reads the lifecycle's own state flow rather than adding an observer from
 * composition, which a scene test's shared, unguarded registry would also take from the main dispatcher.
 */
@Composable
internal fun HoldWhileStarted(key: Any, hold: () -> DisposableHandle) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    DisposableEffect(key, lifecycle) {
        var held: DisposableHandle? = null
        // Undispatched, so a started app takes the hold as the effect enters.
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            lifecycle.currentStateFlow.collect { state ->
                if (state.isAtLeast(Lifecycle.State.STARTED)) {
                    if (held == null) held = hold()
                } else {
                    held?.dispose()
                    held = null
                }
            }
        }
        onDispose {
            watch.cancel()
            held?.dispose()
            held = null
        }
    }
}

/**
 * Travels with the music while [playing]: locked to [pulse]'s beat, one wavelength a beat, its
 * loudness smoothed. On pause the frame loop stops, so phase, loudness and colour all hold the
 * last frame's shape; then, if [zip], a highlight sweeps that path until playback resumes,
 * resting between passes. Resuming carries on from the held shape. TV hardware never animates.
 * With a [scope] the frame loop also traces its windows (see [ScopeTrace]), read by value like
 * [pulse]; paused, the trace holds. [pulse] is read only in the frame loop, never in composition.
 * [previewPhase] and [previewZipMs] are render-harness seams: they pin the frame, as if it had
 * been playing, and take the [scope]'s current window whole.
 */
@Composable
internal fun rememberProgressWave(
    playing: Boolean,
    pulse: (() -> MusicPulse)? = null,
    zip: Boolean = false,
    previewPhase: Float? = null,
    previewZipMs: Long? = null,
    scope: ScopeFrame? = null,
): ProgressWave {
    val tv = LocalTelevisionHardware.current
    if (previewPhase != null) {
        val shownAmplitude = waveAmplitudeTarget(playing, tv, held = 1f)
        val shown = pulse?.invoke() ?: MusicPulse.SILENT
        val zipAt = if (zip && !tv && !playing && previewZipMs != null) previewZipMs else NoZip
        return remember(previewPhase, shownAmplitude, shown, zipAt, scope) {
            ProgressWave(
                mutableFloatStateOf(previewPhase), FixedState(shownAmplitude), mutableFloatStateOf(shown.level),
                mutableLongStateOf(zipAt), mutableLongStateOf(0L), shown.trackLevels.copyOf(), clocked = false,
                scopeTrace = scope?.let { ScopeTrace().apply { prime(it) } },
            )
        }
    }
    // Opened paused there is no shape to hold yet, so it starts flat and rises on play.
    val amplitude = remember { Animatable(waveAmplitudeTarget(playing, tv, held = 0f)) }
    LaunchedEffect(playing, tv) {
        val target = waveAmplitudeTarget(playing, tv, held = amplitude.value)
        if (target != amplitude.value) amplitude.animateTo(target, tween(WaveRiseMillis))
    }
    val phase = remember { mutableFloatStateOf(0f) }
    val level = remember { mutableFloatStateOf(0f) }
    val zipMs = remember { mutableLongStateOf(NoZip) }
    val clock = remember { mutableLongStateOf(0L) }
    val trackLevels = remember { FloatArray(PulsarTrackColors.size) }
    val currentPulse by rememberUpdatedState(pulse)
    val currentScope by rememberUpdatedState(scope)
    val trace = remember(scope != null) { if (scope != null) ScopeTrace() else null }
    val traceTick = remember { mutableIntStateOf(0) }
    if (playing && !tv) {
        LaunchedEffect(trace) {
            var last = withFrameMillis { it }
            var seen: MusicPulse? = null
            var seenAt = last
            var travel = phase.floatValue / TwoPi
            while (true) {
                withFrameMillis { now ->
                    val dt = (now - last).toFloat()
                    clock.longValue += now - last
                    last = now
                    val p = currentPulse?.invoke()
                    if (p !== seen) {
                        seen = p
                        seenAt = now
                    }
                    // Plain Floats throughout, NaN for no beat: a boxed one a frame adds up at 60 Hz.
                    val beat = if (p != null) extrapolatedBeatPhase(p.beatPhase, (now - seenAt).toFloat(), p.msPerBeat) else Float.NaN
                    travel = followBeat(travel, dt, beat, if (p != null) p.msPerBeat else 0f)
                    phase.floatValue = travel * TwoPi
                    val levels = p?.trackLevels
                    for (t in trackLevels.indices) {
                        val target = if (levels != null && t < levels.size) levels[t] else 0f
                        trackLevels[t] = smoothLevel(trackLevels[t], target, dt)
                    }
                    level.floatValue = smoothLevel(level.floatValue, if (p != null) p.level else 0f, dt)
                    // On the play clock, so a pause is not a long run without a trigger.
                    val frame = currentScope
                    if (trace != null && frame != null && trace.update(frame, clock.longValue)) traceTick.intValue++
                }
            }
        }
    }
    if (zip && !tv && !playing) {
        LaunchedEffect(Unit) {
            try {
                val start = withFrameMillis { it }
                while (true) {
                    val t = withFrameMillis { it } - start
                    val inCycle = t.mod((ZipPassMillis + ZipRestMillis).toLong())
                    if (inCycle < ZipPassMillis) {
                        zipMs.longValue = inCycle
                    } else {
                        // Dark through the rest, and no frames asked for until the next pass.
                        zipMs.longValue = NoZip
                        delay(ZipPassMillis + ZipRestMillis - inCycle)
                    }
                }
            } finally {
                zipMs.longValue = NoZip
            }
        }
    }
    return remember(trace) {
        ProgressWave(phase, amplitude.asState(), level, zipMs, clock, trackLevels, clocked = !tv, trace, traceTick)
    }
}

/**
 * The zip band's gradient, refilled for each frame of a pass: its stops across the band's whole
 * width along the elapsed path (0..1, scaled by a span), in a light colour, then dark from a
 * `darkFrom` on, so a gradient that wraps (the ring's sweep) never lights the path's start cap
 * with the band's end. The stops live in primitive arrays, so a frame builds only its shader.
 */
internal class ZipGradient {
    private val positions = FloatArray(ZipStops + 1)
    private val colors = LongArray(ZipStops + 1)
    private val positionList = object : AbstractList<Float>() {
        override val size: Int get() = positions.size
        override fun get(index: Int): Float = positions[index]
    }
    private val colorList = object : AbstractList<Color>() {
        override val size: Int get() = colors.size
        override fun get(index: Int): Color = Color(colors[index].toULong())
    }

    private fun fill(timeMs: Long, light: Color, span: Float, darkFrom: Float) {
        val centre = zipCentre(timeMs)
        for (j in 0 until ZipStops) {
            val position = (centre + ZipHalfWidth * (2f * j / (ZipStops - 1) - 1f)).coerceIn(0f, 1f)
            positions[j] = position * span
            colors[j] = light.copy(alpha = ZipAlpha * zipIntensity(position, timeMs)).value.toLong()
        }
        positions[ZipStops] = darkFrom
        colors[ZipStops] = Color.Transparent.value.toLong()
    }

    /** The ring's band, swept around [center]. */
    fun sweep(timeMs: Long, light: Color, span: Float, darkFrom: Float, center: Offset): Brush {
        fill(timeMs, light, span, darkFrom)
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader = SweepGradientShader(center, colorList, positionList)
        }
    }
}

/** The ring's colour now: the music's once it has played (held through a pause), its own [color] while flat. */
private fun ringWaveColor(wave: ProgressWave, color: Color): Color =
    lerp(color, blendTrackColors(wave.trackLevels, PulsarTrackColors, color), wave.amplitude.coerceIn(0f, 1f))

/** The ring's strokes for a box of [size], built once per size in a draw cache rather than every frame. */
internal class RingStrokes(size: Size) {
    val width = size.minDimension * RingStrokeShare
    val track = Stroke(width)
    val line = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val scopeWidth = size.minDimension * ScopeStrokeShare
    val scope = Stroke(scopeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/** The ring with no progress: the dim track, all the way round. */
internal fun DrawScope.drawProgressRingTrack(color: Color, strokes: RingStrokes) {
    val radius = (size.minDimension - strokes.width) / 2 - size.minDimension * RingAmplitudeShare
    drawCircle(color.copy(alpha = 0.2f), radius, center, style = strokes.track)
}

/**
 * The transport ring: elapsed arc from 12 o'clock, the rest a dim flat track. With a scope the arc
 * traces it, the newest audio at the playhead, in a finer line; without one it is a plain arc.
 * Stroke and height are shares of the ring's size. Paused, the zip lights the held shape.
 */
internal fun DrawScope.drawProgressRing(
    path: Path,
    progress: Float,
    wave: ProgressWave,
    color: Color,
    strokes: RingStrokes,
    zip: ZipGradient,
) {
    // Pulled in by the full amplitude so a crest never leaves the box.
    val radius = ringCentreRadius(size.minDimension)
    val track = color.copy(alpha = 0.2f)
    val p = progress.coerceIn(0f, 1f)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    drawArc(track, -90f + 360f * p, 360f * (1f - p), false, topLeft, arcSize, style = strokes.track)
    val fill = ringWaveColor(wave, color)
    val scope = wave.scope
    val stroke = if (scope != null) strokes.scopeWidth else strokes.width
    val line = if (scope != null) strokes.scope else strokes.line
    val amplitude = if (scope == null || scope.size == 0) {
        0f
    } else {
        scopeAmplitude(size.minDimension) * wave.amplitude * scopeArcFade(TwoPi * radius * p / size.minDimension)
    }
    val flat = amplitude == 0f
    // Turned a quarter back, so the sweep gradient's start sits at 12 o'clock with the arc's.
    rotate(-90f) {
        if (flat || scope == null) {
            // No feed, opened paused, TV, or no trace yet: one arc, not a polyline.
            drawArc(fill, 0f, 360f * p, false, topLeft, arcSize, style = line)
        } else {
            path.reset()
            path.addQuarterTurnedScopeArc(
                center, radius, p, amplitude, scope,
                step = 1.dp.toPx() / ScopePointsPerDp, blur = ScopeBlur.toPx(), taper = size.minDimension * ScopeTaperShare,
            )
            drawPath(path, fill, style = line)
        }
        val zipAt = wave.zipMs
        if (zipAt >= 0 && TwoPi * radius * p >= ZipMinLength.toPx()) {
            // Dark again past the playhead's round cap (half the stroke, as a fraction of the turn).
            val capEnd = (p + stroke / (4f * PI.toFloat() * radius)).coerceAtMost(1f)
            val brush = zip.sweep(zipAt, fill.lighten(ZipLighten), p, capEnd, center)
            if (flat) drawArc(brush, 0f, 360f * p, false, topLeft, arcSize, style = line) else drawPath(path, brush, style = line)
        }
    }
}

/** Where the playhead sits along a track [width] px wide. */
internal fun playheadX(width: Float, progress: Float): Float = width * progress.coerceIn(0f, 1f)
