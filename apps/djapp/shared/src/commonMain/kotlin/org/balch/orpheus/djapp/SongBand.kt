package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarTrackColors
import org.balch.orpheus.features.pulsar.SongStory
import org.balch.orpheus.features.pulsar.StoryBeat
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** The dock's song band, its own row under the top bar, on TV hardware too. */
internal val SongBandHeight = 36.dp

/** 3dp bars 2dp apart: about 248 across a 1280dp window's band and 184 across 960dp, a bar per beat or two. */
internal val SongBandBarWidth = 3.dp
internal val SongBandBarGap = 2.dp

/** The bars rise from this line, low enough that the tallest cap clears the top and high enough for the playhead dot below. */
private val SongBandBaseline = 30.dp
private val SongBandTallest = 24.dp
private val SongBandTrackStroke = 2.dp

/**
 * A soft shade under the band. Over a bright, busy visualization the track colours washed out
 * without it, a pink breakdown worst of all over a warm sunset.
 */
internal val SongBandScrim = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))

/** The bars before the playhead that follow the live loudness, and how far the beat swings them. */
private const val LiveBars = 5
private const val BeatSwing = 0.25f

/** Beat phase between neighbouring live bars, in radians: the swing ripples toward the playhead. */
private const val RippleStep = 0.9f

/**
 * Where the band's bars go: [count] even slots across [width] px, one per stretch of the song, each
 * bar [barWidth] wide and centred in its slot, rising from [baseline] by up to [tallest].
 */
internal class SongBandLayout(
    val width: Float,
    val barWidth: Float,
    gap: Float,
    val baseline: Float,
    val tallest: Float,
) {
    val count: Int = ((width + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
    val slot: Float = width / count

    fun centerX(bar: Int): Float = slot * (bar + 0.5f)

    /** The bars whose centre the playhead at [playX] has passed. */
    fun reached(playX: Float): Int = floor(playX / slot + 0.5f).toInt().coerceIn(0, count)

    /** A bar's rise above the baseline for a stretch this loud; its round caps add half a width at each end. */
    fun height(level: Float): Float = tallest * level.coerceIn(0f, 1f)
}

internal fun Density.songBandLayout(width: Float): SongBandLayout = SongBandLayout(
    width = width,
    barWidth = SongBandBarWidth.toPx(),
    gap = SongBandBarGap.toPx(),
    baseline = SongBandBaseline.toPx(),
    tallest = SongBandTallest.toPx(),
)

/** A stretch nobody heard, in [SongBandShape]'s colours: it wears the accent, which the draw supplies. */
private val Unheard = Color.Unspecified.value.toLong()

/**
 * The reached bars' loudest beat and lead colour, rebuilt by [SongBandCache], never per frame, and
 * never for the accent: a stretch nobody heard takes it at draw time. A cache's shape shares its
 * arrays with the next one it builds.
 */
internal class SongBandShape(val bars: Int, val levels: FloatArray, private val colors: LongArray) {
    /** Whether bar [bar] was heard: a stretch that was not is a dimmed dot in the accent. */
    fun heard(bar: Int): Boolean = colors[bar] != Unheard

    /** Bar [bar]'s colour, the [accent] dimmed where nobody heard it. */
    fun color(bar: Int, accent: Color): Color =
        if (heard(bar)) Color(colors[bar].toULong()) else accent.copy(alpha = UnheardAlpha)
}

/**
 * Buckets [story] into the first [bars] slots of [layout]. The elapsed time is stretched to those
 * slots' end, so a slot is always the same stretch of song however far the playhead is into it.
 */
private fun fillSongBandShape(
    story: SongStory,
    layout: SongBandLayout,
    bars: Int,
    playX: Float,
    elapsedMs: Long,
    levels: FloatArray,
    colors: LongArray,
    energy: FloatArray,
): SongBandShape {
    if (bars > 0) {
        val endMs = (elapsedMs * (bars * layout.slot / playX)).toLong()
        storyLevelsInto(levels, bars, story, endMs)
        // Silence and stretches the app did not watch stay a dimmed dot: played, not heard.
        storyColorsInto(colors, energy, bars, story, PulsarTrackColors, Color.Unspecified, endMs)
    }
    return SongBandShape(bars, levels, colors)
}

/**
 * The band's [SongBandShape], rebuilt when a beat lands or the playhead passes a bar's centre: not
 * for a loop-cycle's progress tick, which leaves the beats as they were. One per draw cache, filling
 * the same arrays each time.
 */
internal class SongBandCache {
    private var beats: List<StoryBeat>? = null
    private var reached = -1
    private var count = -1
    private var shape: SongBandShape? = null
    private var levels = FloatArray(0)
    private var colors = LongArray(0)
    private var energy = FloatArray(0)

    fun shapeFor(songStory: SongStory, layout: SongBandLayout, playX: Float, elapsedMs: Long): SongBandShape {
        val bars = layout.reached(playX)
        val cached = shape
        if (cached != null && songStory.beats === beats && bars == reached && layout.count == count) return cached
        if (levels.size < layout.count) {
            levels = FloatArray(layout.count)
            colors = LongArray(layout.count)
            energy = FloatArray(layout.count * PulsarTrackColors.size)
        }
        val filled = if (bars <= 0 || playX <= 0f) 0 else bars
        return fillSongBandShape(songStory, layout, filled, playX, elapsedMs, levels, colors, energy).also {
            beats = songStory.beats
            reached = bars
            count = layout.count
            shape = it
        }
    }
}

/** The band with no progress: the dim track, end to end. */
internal fun DrawScope.drawSongBandTrack(layout: SongBandLayout, color: Color) {
    val baseline = layout.baseline
    drawLine(color.copy(alpha = 0.2f), Offset(0f, baseline), Offset(layout.width, baseline), SongBandTrackStroke.toPx())
}

/**
 * The song band: one bar per stretch of the song so far, its height the loudness and its colour
 * the lead track's, up to a playhead dot on a dim flat track. While playing, the last few bars
 * follow the live loudness and colour and swing on the beat; paused they hold and the zip sweeps.
 *
 * The bars behind that live head have settled: they are recorded into [heard] and [unheard] and
 * replayed, so a frame issues only the live bars, the zip's lit ones and the playhead. The heard
 * bars are recorded again when the shape changes; the unheard dots, in the accent, when it does too.
 * Without a live head (TV hardware) every bar is settled. One per draw cache, which owns both layers.
 */
internal class SongBandPainter(
    private val layout: SongBandLayout,
    private val heard: GraphicsLayer,
    private val unheard: GraphicsLayer,
) {
    private val shapes = SongBandCache()
    private var heardShape: SongBandShape? = null
    private var unheardShape: SongBandShape? = null
    private var unheardAccent = Color.Unspecified
    private var anyUnheard = false

    fun DrawScope.draw(story: SongStory, progress: Float, elapsedMs: Long, wave: ProgressWave, accent: Color, liveHead: Boolean = true) {
        val baseline = layout.baseline
        val playX = playheadX(layout.width, progress)
        drawLine(accent.copy(alpha = 0.2f), Offset(playX, baseline), Offset(layout.width, baseline), SongBandTrackStroke.toPx())
        val shape = shapes.shapeFor(story, layout, playX, elapsedMs)
        // Past the live head a bar's level and colour are the shape's alone.
        val settled = (shape.bars - if (liveHead) LiveBars else 0).coerceAtLeast(0)
        val layerSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        if (heardShape !== shape) {
            heardShape = shape
            anyUnheard = false
            for (k in 0 until settled) if (!shape.heard(k)) anyUnheard = true
            heard.record(this, layoutDirection, layerSize) {
                for (k in 0 until settled) if (shape.heard(k)) drawBar(k, shape.levels[k], shape.color(k, accent))
            }
        }
        if (anyUnheard && (unheardShape !== shape || unheardAccent != accent)) {
            unheardShape = shape
            unheardAccent = accent
            unheard.record(this, layoutDirection, layerSize) {
                for (k in 0 until settled) if (!shape.heard(k)) drawBar(k, shape.levels[k], shape.color(k, accent))
            }
        }
        drawLayer(heard)
        if (anyUnheard) drawLayer(unheard)
        val zipAt = if (playX >= ZipMinLength.toPx()) wave.zipMs else -1L
        if (zipAt >= 0) for (k in 0 until settled) drawZip(k, shape.levels[k], shape.color(k, accent), playX, zipAt)
        val live = wave.level.coerceIn(0f, 1f) * wave.amplitude
        val liveColor = blendTrackColors(wave.trackLevels, PulsarTrackColors, accent)
        val head = shape.bars - 1
        for (k in settled..head) {
            val back = head - k
            val near = (1f - back.toFloat() / LiveBars).coerceIn(0f, 1f)
            var level = shape.levels[k]
            var barColor = shape.color(k, accent)
            if (near > 0f) {
                level += (live - level) * near
                level *= 1f + BeatSwing * near * wave.amplitude * cos(wave.phase + back * RippleStep)
                barColor = lerp(barColor, liveColor, near * wave.amplitude.coerceIn(0f, 1f))
            }
            drawBar(k, level, barColor)
            if (zipAt >= 0) drawZip(k, level, barColor, playX, zipAt)
        }
        drawCircle(accent.copy(alpha = 0.9f), PlayheadRadius.toPx(), Offset(playX, baseline))
    }

    private fun DrawScope.drawBar(bar: Int, level: Float, color: Color) {
        val half = layout.barWidth / 2
        val topLeft = Offset(layout.centerX(bar) - half, layout.baseline - layout.height(level) - half)
        drawRoundRect(color, topLeft, Size(layout.barWidth, layout.baseline + half - topLeft.y), CornerRadius(half))
    }

    // Over its own bar only, so drawn after all of them it lands just as it did straight after each.
    private fun DrawScope.drawZip(bar: Int, level: Float, color: Color, playX: Float, zipAt: Long) {
        val lit = zipIntensity(layout.centerX(bar) / playX, zipAt)
        if (lit > 0f) drawBar(bar, level, color.lighten(ZipLighten).copy(alpha = ZipAlpha * lit))
    }
}

/** The bars the playhead has reached at [progress], or -1 with no progress: what a static band redraws on. */
internal fun SongBandLayout.reachedAt(progress: Float?): Int =
    if (progress == null) -1 else reached(playheadX(width, progress))

/**
 * The dock's song band, a full-width row under the top bar within the bars' side insets. Its bars
 * are read only in draw: a beat or a playhead step redraws the band's layer, never recomposes it.
 * On TV hardware it is static, with no pulse, no live head and no zip, and it redraws only when the
 * playhead reaches another bar.
 */
@Composable
internal fun DockSongBand(
    pulsarFeature: PulsarFeature,
    modifier: Modifier = Modifier,
    // Render-harness seams only: pin the live head's wave phase and the paused zip, see rememberProgressWave.
    previewWavePhase: Float? = null,
    previewZipMs: Long? = null,
) {
    val navState = pulsarFeature.vibeNavFlow.collectAsStateWithLifecycle()
    // Only play/pause drives the wave, so other Pulsar state changes never recompose the band.
    val playing by remember(pulsarFeature) {
        pulsarFeature.stateFlow.map { !it.globalPaused }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = !pulsarFeature.stateFlow.value.globalPaused)
    // The story and the pulse are read only in draw and the wave's frame loop. TV hardware runs no
    // frame loop, so there nothing reads the pulse and nothing holds it.
    val live = !LocalTelevisionHardware.current
    val story = pulsarFeature.songStoryFlow.collectAsStateWithLifecycle()
    val pulse: (() -> MusicPulse)? = if (live) rememberMusicPulse(pulsarFeature) else null
    val density = LocalDensity.current
    val widthPx = remember { mutableIntStateOf(0) }
    // Only a live wave plays and zips, so TV hardware reads no progress here at all.
    var hasProgress = false
    var zipLongEnough = false
    if (live) {
        hasProgress = remember(navState) { derivedStateOf { navState.value.progress != null } }.value
        zipLongEnough = remember(navState, density) {
            derivedStateOf { with(density) { widthPx.intValue.toDp() } * (navState.value.progress ?: 0f) >= ZipMinLength }
        }.value
    }
    val wave = rememberProgressWave(
        playing = playing && hasProgress, pulse = pulse, zip = hasProgress && zipLongEnough,
        previewPhase = previewWavePhase, previewZipMs = previewZipMs,
    )
    // The playhead runs on between the tracker's loop-cycle updates; read only in draw.
    val smooth = rememberSmoothProgress(wave) { navState.value.songPosition() }
    // TV: the draw follows this alone, so a beat or a progress tick inside one bar asks for no frame.
    val reachedBar: State<Int>? = if (live) null else remember(navState, density) {
        derivedStateOf { density.songBandLayout(widthPx.intValue.toFloat()).reachedAt(navState.value.progress) }
    }
    val accent = rememberDockAccent()

    Spacer(
        modifier
            .fillMaxWidth()
            .height(SongBandHeight)
            .dockAccent(accent)
            // Outside the band's layer, so it draws once rather than on every wave frame.
            .drawBehind { drawRect(SongBandScrim) }
            .windowInsetsPadding(platformSafeAreaInsets().only(WindowInsetsSides.Start + WindowInsetsSides.End))
            .padding(horizontal = TvBottomBarSidePadding)
            .onSizeChanged { widthPx.intValue = it.width }
            // Its own layer: a wave frame re-records the band alone. The bars are rebuilt when a beat
            // lands or the playhead passes one, and those behind the live head are replayed.
            .graphicsLayer()
            .drawWithCache {
                val layout = songBandLayout(size.width)
                val band = SongBandPainter(layout, obtainGraphicsLayer(), obtainGraphicsLayer())
                onDrawBehind {
                    val tint = accent.color()
                    if (reachedBar == null) {
                        drawDockBand(band, layout, navState.value.progress, story.value, smooth, wave, tint, liveHead = true)
                    } else {
                        reachedBar.value
                        Snapshot.withoutReadObservation {
                            drawDockBand(band, layout, navState.value.progress, story.value, smooth, wave, tint, liveHead = false)
                        }
                    }
                }
            },
    )
}

private fun DrawScope.drawDockBand(
    band: SongBandPainter,
    layout: SongBandLayout,
    coarse: Float?,
    songStory: SongStory,
    smooth: SmoothProgress,
    wave: ProgressWave,
    tint: Color,
    liveHead: Boolean,
) {
    if (coarse == null) {
        drawSongBandTrack(layout, tint)
        return
    }
    with(band) {
        draw(songStory, smooth.fractionOr(coarse), smooth.positionMsOr(songStory.elapsedMs), wave, tint, liveHead)
    }
}
