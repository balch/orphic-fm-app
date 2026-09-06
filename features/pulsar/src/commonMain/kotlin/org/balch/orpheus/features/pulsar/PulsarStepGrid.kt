package org.balch.orpheus.features.pulsar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.infrastructure.liquefiableVizEffects
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten

/** Track colors: Kick, Perc, HiHat, Bass, Keys, Pad, Texture, FX */
internal val TrackColors = listOf(
    Color(0xFFE53935), // 0 KICK - red
    Color(0xFFFF9800), // 1 PERC - orange
    Color(0xFFFFEB3B), // 2 HIHAT - yellow
    Color(0xFF42A5F5), // 3 BASS - blue
    Color(0xFF66BB6A), // 4 KEYS - green
    Color(0xFF26C6DA), // 5 PAD - cyan
    Color(0xFFEC407A), // 6 TEXTURE - pink
    Color(0xFFBDBDBD), // 7 FX - muted white
)

private const val NUM_TRACKS = 8
private const val MAX_STEPS = 32

// Stroke is a class, not a value class: build each once rather than per shape per frame.
private val SelectionStroke = Stroke(width = 1.5f)
private val CellBorderStroke = Stroke(width = 1f)
private val TraceGlowStroke = Stroke(width = 4f)
private val TraceStroke = Stroke(width = 1.5f)

/**
 * The embossed cell's highlight and shadow gradients, one pair per track, rebuilt only when
 * that track's cell size changes. Both run from the cell's top-left corner to its bottom-right,
 * so they are drawn inside a translate() to the cell, and the cell's alpha goes on the draw
 * call rather than into the stops. Before this, every lit cell built both gradients every
 * frame: ~120 gradient objects and Skia shaders per frame, most of the grid's garbage.
 */
private class CellBrushCache {
    private val widths = FloatArray(NUM_TRACKS) { -1f }
    private val heights = FloatArray(NUM_TRACKS) { -1f }
    private val highlights = arrayOfNulls<Brush>(NUM_TRACKS)
    private val shadows = arrayOfNulls<Brush>(NUM_TRACKS)

    fun highlight(track: Int, w: Float, h: Float): Brush {
        refresh(track, w, h)
        return highlights[track]!!
    }

    fun shadow(track: Int, w: Float, h: Float): Brush {
        refresh(track, w, h)
        return shadows[track]!!
    }

    private fun refresh(track: Int, w: Float, h: Float) {
        if (widths[track] == w && heights[track] == h) return
        widths[track] = w
        heights[track] = h
        highlights[track] = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.3f),
            0.3f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(w, h),
        )
        shadows[track] = Brush.linearGradient(
            0f to Color.Transparent,
            0.7f to Color.Black.copy(alpha = 0.15f),
            1f to Color.Black.copy(alpha = 0.3f),
            start = Offset.Zero,
            end = Offset(w, h),
        )
    }
}

/**
 * Canvas-drawn step grid visualization showing 8 tracks x up to 32 steps.
 * Features glowing cells, playhead afterglow trail, waveform traces,
 * beat pulse, and liquid glass overlay.
 *
 * [vizData] is a State rather than a value on purpose. The engine emits a new PulsarVizData
 * every 16ms during playback; taken as a plain parameter it recomposed this whole composable
 * at 60Hz, and the frame loop below, a LaunchedEffect(Unit), kept the first emission forever
 * and never saw a playhead move. It is read only inside draw lambdas and that loop, so an
 * emission invalidates the draw and nothing else.
 */
@Composable
fun PulsarStepGrid(
    vizData: State<PulsarVizData>,
    trackVizFlows: List<StateFlow<FloatArray>> = List(NUM_TRACKS) { MutableStateFlow(FloatArray(0)) },
    energy: Float = 0.5f,
    space: Float = 0.4f,
    complexity: Float = 0.3f,
    mood: Float = 0.5f,
    selectedTrack: Int? = null,
    onTrackSelected: (Int?) -> Unit = {},
    arrangementState: PulsarArrangementState? = null,
    arrangement: Arrangement? = null,
    activeTransition: TransitionStyle? = null,
    finalSectionIndex: Int = -1,
    pendingTransition: TransitionStyle? = null,
    modifier: Modifier = Modifier,
) {
    val cellGap = 2f
    val trackGap = 4f

    val cellBrushes = remember { CellBrushCache() }
    val tracePaths = remember { List(NUM_TRACKS) { Path() } }
    // No whole-grid transform lives here. A 3% beat-pulse scale on the outer graphicsLayer used
    // to fire on every step advance of track 0, which repainted 84% of the canvas on the step
    // frame and 83% on the next one; at high energy, where steps land every few frames, that
    // read as the screen shimmering. It had never actually rendered before the frame loop
    // started seeing live data, so removing it restores what shipped rather than changing it.
    // A pulse on real kick hits, decaying over ~150ms, would be the version worth having.
    val smoothedLevels = remember { FloatArray(NUM_TRACKS) }
    // Running peak per track for stable waveform normalization (slow decay)
    val trackPeaks = remember { FloatArray(NUM_TRACKS) { 0.1f } }
    // Per-track waveform State references. `.value` is intentionally NOT read
    // in the composition phase — every flow emission would otherwise recompose
    // the entire grid (8 audio-buffer-rate flows ≫ 60Hz). Reads happen inside
    // the waveform Canvas's drawScope below, which invalidates only the draw.
    val trackWaveformStates = trackVizFlows.map { it.collectAsState() }

    // The only composition-phase readers of the viz data: the frame loop's gate and the glass
    // layers. Each takes exactly the field it needs through derivedStateOf, so the body
    // recomposes when playback starts or stops and when the step count changes, not per
    // emission.
    val isPlaying by remember(vizData) { derivedStateOf { vizData.value.playheads[0] >= 0 } }
    val steps0 by remember(vizData) {
        derivedStateOf { vizData.value.stepCounts[0].coerceAtMost(MAX_STEPS) }
    }
    val playhead0 by remember(vizData) { derivedStateOf { vizData.value.playheads[0] } }

    // Runs only while there is something to animate: playback (level smoothing and the beat
    // pulse) or a pulse still settling after a stop. Between songs it parks in snapshotFlow
    // waiting on the play state alone, so nothing holds the frame clock and Android's display
    // pipeline can idle while the app sits open. Parking there rather than re-keying the
    // effect matters: the state write that starts playback wakes it before that frame's clock
    // fires, so the first step is animated on the frame it belongs to.
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { isPlaying }.first { it }
            var lastNanos = 0L
            while (isPlaying) {
                withFrameNanos { nanos ->
                    val viz = vizData.value
                    val dt = if (lastNanos == 0L) 0.016f
                    else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    lastNanos = nanos

                    // Smooth track levels: fast attack (~10ms), slow decay (~300ms)
                    for (t in 0 until NUM_TRACKS) {
                        val raw = viz.trackLevels[t].coerceIn(0f, 1f)
                        if (raw > smoothedLevels[t]) {
                            smoothedLevels[t] += (raw - smoothedLevels[t]) * (1f - kotlin.math.exp(-dt / 0.01f))
                        } else {
                            smoothedLevels[t] *= (1f - dt * 3f).coerceAtLeast(0f)
                        }
                        // Running peak for waveform normalization: fast attack, very slow decay (~10s)
                        if (raw > trackPeaks[t]) {
                            trackPeaks[t] = raw
                        } else {
                            trackPeaks[t] *= (1f - dt * 0.1f).coerceAtLeast(0f)  // ~10s decay
                            if (trackPeaks[t] < 0.01f) trackPeaks[t] = 0.01f     // floor
                        }
                    }
                }
            }
        }
    }

    // Local liquid state so the glass frosts the grid Canvas, not the app background
    val gridLiquidState = rememberLiquidState()
    val frost = space * 0.6f + mood * 0.2f + (1f - energy) * 0.2f
    val refraction = mood * 0.5f + complexity * 0.3f + space * 0.2f
    val saturation = energy * 0.5f + mood * 0.3f + (1f - space) * 0.2f
    val dispersion = complexity * 0.4f + energy * 0.3f + mood * 0.3f
    val liquidScope = VisualizationLiquidScope(
        refraction = refraction,
        saturation = 0.5f + saturation * 1.5f,  // 0.5-2.0 range
        dispersion = dispersion,
        curve = 0.001f,          // lens curvature follows refraction
    )

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0A0A12))
            .pointerInput(selectedTrack) {
                detectTapGestures { offset ->
                    val totalTrackGaps = (NUM_TRACKS - 1) * trackGap
                    val trackHeight = (size.height.toFloat() - totalTrackGaps) / NUM_TRACKS
                    val tappedTrack = (offset.y / (trackHeight + trackGap)).toInt()
                        .coerceIn(0, NUM_TRACKS - 1)
                    onTrackSelected(if (tappedTrack == selectedTrack) null else tappedTrack)
                }
            }
    ) {

        // Layer 0: Canvas draws cells and glow
        Canvas(
            modifier = Modifier.matchParentSize()
                .liquefiableVizEffects(gridLiquidState)
        ) {
            // Read in the draw phase: an emission redraws this Canvas, nothing recomposes.
            val viz = vizData.value
            val totalTrackGaps = (NUM_TRACKS - 1) * trackGap
            val trackHeight = (size.height - totalTrackGaps) / NUM_TRACKS

            // Layer 2-5: Cells, glow, playhead, selection
            for (track in 0 until NUM_TRACKS) {
                val rawSteps = viz.stepCounts[track]
                if (rawSteps < 2) continue  // no data yet — skip track
                val steps = rawSteps.coerceAtMost(MAX_STEPS)
                val trackY = track * (trackHeight + trackGap)
                val totalCellGaps = (steps - 1) * cellGap
                val cellWidth = (size.width - totalCellGaps) / steps
                val color = TrackColors[track]
                val playhead = viz.playheads[track]

                // Selection highlight border
                if (track == selectedTrack) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.3f),
                        topLeft = Offset(-2f, trackY - 2f),
                        size = Size(size.width + 4f, trackHeight + 4f),
                        cornerRadius = CornerRadius(4f, 4f),
                        style = SelectionStroke,
                    )
                }

                for (step in 0 until steps) {
                    val cellX = step * (cellWidth + cellGap)
                    val gate = viz.stepGates[track][step]
                    val velocity = viz.stepVelocities[track][step].coerceIn(0f, 1f)
                    val isAtPlayhead = step == playhead
                    val trackLevel = smoothedLevels[track].coerceIn(0f, 1f)
                    val corner = CornerRadius(3f, 3f)

                    // Background cell
                    drawRoundRect(
                        color = color.copy(alpha = 0.08f),
                        topLeft = Offset(cellX, trackY),
                        size = Size(cellWidth, trackHeight),
                        cornerRadius = corner,
                    )

                    // Active cell — raised embossed square, drawn in the cell's own frame:
                    // translate to it and scale the playhead pulse about its centre, so the
                    // cached gradients need no per-cell geometry and the alpha rides the call.
                    if (gate) {
                        val baseAlpha = if (isAtPlayhead) {
                            (0.4f + trackLevel * 0.6f).coerceAtMost(1f)
                        } else {
                            velocity * 0.7f
                        }
                        val pulse = if (isAtPlayhead) 1f + trackLevel * 0.1f else 1f
                        val cell = Size(cellWidth, trackHeight)

                        withTransform({
                            translate(cellX, trackY)
                            if (pulse != 1f) scale(pulse, pulse, Offset(cellWidth / 2, trackHeight / 2))
                        }) {
                            // Glow halo
                            if (isAtPlayhead && trackLevel > 0.02f) {
                                val glowPad = 2f + trackLevel * 5f
                                drawRoundRect(
                                    color = color.copy(alpha = trackLevel * 0.4f),
                                    topLeft = Offset(-glowPad, -glowPad),
                                    size = Size(cellWidth + glowPad * 2, trackHeight + glowPad * 2),
                                    cornerRadius = CornerRadius(4f, 4f),
                                    blendMode = BlendMode.Plus,
                                )
                            }

                            // Solid fill
                            drawRoundRect(
                                color = color.copy(alpha = baseAlpha),
                                size = cell,
                                cornerRadius = corner,
                            )

                            // Top-left highlight
                            drawRoundRect(
                                brush = cellBrushes.highlight(track, cellWidth, trackHeight),
                                size = cell,
                                cornerRadius = corner,
                                alpha = baseAlpha,
                            )

                            // Bottom-right shadow — depth
                            drawRoundRect(
                                brush = cellBrushes.shadow(track, cellWidth, trackHeight),
                                size = cell,
                                cornerRadius = corner,
                                alpha = baseAlpha,
                            )

                            // Border
                            drawRoundRect(
                                color = color.copy(alpha = baseAlpha * 0.5f),
                                size = cell,
                                cornerRadius = corner,
                                style = CellBorderStroke,
                            )
                        }
                    }

                }
            }

            // (signal traces drawn ON TOP of glass — see overlay Canvas below)
        }

        // Layer 7: Liquid glass overlay — only when playing
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .liquidVizEffects(
                        gridLiquidState,
                        liquidScope,
                        (frost * 3f).dp,
                        OrpheusColors.cosmicPurple,
                        0.08f,
                        RoundedCornerShape(6.dp),
                    )
            )
        }

        // Layer 7.5: Playhead glass column — its own glass effect
        // 2x the base glass params except frost which is 0.5x
        if (isPlaying && steps0 >= 2) {
            val steps = steps0
            val totalCellGaps = (steps - 1) * cellGap
            val gridWidthDp = maxWidth.value  // actual rendered width from BoxWithConstraints
            val cellW = (gridWidthDp - totalCellGaps) / steps

            val playheadScope = VisualizationLiquidScope(
                refraction = (refraction * 2f).coerceAtMost(1f),
                saturation = (0.5f + saturation * 1.5f * 2f).coerceAtMost(2f),
                dispersion = (dispersion * 0.5f * 2f).coerceAtMost(1f),
                curve = (refraction * 0.5f * 2f).coerceAtMost(1f),
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(cellW.dp)
                    // The lambda form reads the playhead in the layout phase, so the column
                    // moves each step without recomposing anything.
                    .offset {
                        val phPos = playhead0.coerceIn(0, steps - 1)
                        IntOffset((phPos * (cellW + cellGap)).dp.roundToPx(), 0)
                    }
                    .liquidVizEffects(
                        gridLiquidState,
                        playheadScope,
                        (4f + frost * 20f).dp,  // heavy frost — always visible
                        OrpheusColors.cosmicPurple,
                        0.5f,
                        RoundedCornerShape(2.dp),
                    )
            )
        }

        // Layer 8: Signal traces ON TOP of glass — real waveforms like other panels
        Canvas(modifier = Modifier.matchParentSize()) {
            val viz = vizData.value
            val totalTrackGaps = (NUM_TRACKS - 1) * trackGap
            val trackHeight = (size.height - totalTrackGaps) / NUM_TRACKS
            val boldness = 1f + frost * 2f

            for (track in 0 until NUM_TRACKS) {
                if (viz.stepCounts[track] < 2) continue
                // Read inside drawScope so flow emissions only invalidate this draw.
                val data = trackWaveformStates[track].value
                if (data.isEmpty()) continue

                val trackY = track * (trackHeight + trackGap)
                val color = TrackColors[track]
                // Anchor at 85% down (15% from bottom), peaks reach 7% from top
                val baseY = trackY + trackHeight * 0.85f
                val maxH = trackHeight * 0.78f  // 85% - 7% = 78% range

                // Use running peak for stable normalization (not per-frame max)
                val maxVal = trackPeaks[track].coerceAtLeast(0.01f)

                val path = tracePaths[track].apply { rewind() }
                val xStep = size.width / data.size.coerceAtLeast(1)

                var prevY = baseY
                data.forEachIndexed { i, v ->
                    val x = i * xStep
                    // Normalize — values are positive peaks, map 0..max to 0..1
                    val normalized = (v / maxVal).coerceIn(0f, 1f)
                    // Square filter: quantize to 5 levels
                    val quantized = (normalized * 4f).toInt() / 4f
                    // Anchor at baseY (bottom), grow upward
                    val y = baseY - quantized * maxH

                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        // Staircase: hold horizontal, then jump vertical
                        path.lineTo(x, prevY)
                        path.lineTo(x, y)
                    }
                    prevY = y
                }
                path.lineTo(size.width, prevY)

                // Glow — scales with frost/boldness
                drawPath(
                    path = path,
                    color = color.copy(alpha = (0.08f * boldness).coerceAtMost(0.25f)),
                    style = TraceGlowStroke,
                    blendMode = BlendMode.Plus,
                )
                // Main trace
                drawPath(
                    path = path,
                    color = color.copy(alpha = (0.25f * boldness).coerceAtMost(0.6f)),
                    style = TraceStroke,
                )
            }
        }

        // Layer 9: Arrangement state overlay

        val sectionName = arrangementState?.sectionIndex?.let { sectionIndex ->
            arrangement?.sections?.getOrNull(sectionIndex)?.name
        }

        if (sectionName != null || activeTransition != null) {
            // While a transition is firing the overlay reads "\u25b8 STYLE" in
            // cosmicPurple \u2014 louder than the normal section/bar text because
            // it's the moment the user is most likely curious about.
            //
            // Otherwise: "section bar X/Y [\u25b8 solo]" in white, with a trailing
            // " \u2014 STYLE" suffix in cosmicPurple when the current section is
            // the final section and a transition is pending. AnnotatedString
            // lets the suffix carry its own color inline.
            val overlay: AnnotatedString =
                buildAnnotatedString {
                    val accent = OrpheusColors.cosmicPurple.lighten()
                    val base = Color.White.copy(alpha = 0.9f)
                    if (activeTransition != null) {
                        pushStyle(SpanStyle(color = accent))
                        append("\u25b8 ${activeTransition.name}")
                        pop()
                    } else {
                        val currentBar = (arrangementState?.barsElapsed ?: 0) + 1
                        val barText = "$currentBar/${arrangementState?.barsTotal ?: 0}"
                        val soloText = if (arrangementState != null && arrangementState.soloActive && arrangementState.soloTrack >= 0) {
                            val name = if (arrangementState.bandSolo) {
                                arrangementState.bandMemberNames.getOrElse(arrangementState.soloTrack) { "?" }
                            } else {
                                PULSAR_TRACK_NAMES.getOrElse(arrangementState.soloTrack) { "?" }
                            }
                            " \u25b8 $name"
                        } else ""
                        pushStyle(SpanStyle(color = base))
                        append("$sectionName $barText$soloText")
                        pop()
                        val isFinal = arrangementState != null
                            && finalSectionIndex >= 0
                            && arrangementState.sectionIndex == finalSectionIndex
                            && pendingTransition != null
                        if (isFinal) {
                            pushStyle(SpanStyle(color = accent))
                            append(" \u2014 ${pendingTransition.name}")
                            pop()
                        }
                    }
                }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(
                        OrpheusColors.deepPurple.copy(alpha = 0.4f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = overlay,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Suppress("MagicNumber")
@Preview(widthDp = 500, heightDp = 120)
@Preview(widthDp = 500, heightDp = 120, name = "140%", fontScale = 1.4f)
@Composable
private fun PulsarStepGridPreview() {
    OrpheusTheme {
        // Sample "Cosmic Techno" data
        val gates = Array(NUM_TRACKS) { BooleanArray(MAX_STEPS) }
        val velocities = Array(NUM_TRACKS) { FloatArray(MAX_STEPS) }
        val stepCounts = intArrayOf(16, 16, 16, 16, 16, 16, 16, 16)

        // Kick: four-on-the-floor
        for (i in listOf(0, 4, 8, 12)) { gates[0][i] = true; velocities[0][i] = 0.9f }
        // Perc: offbeat
        for (i in listOf(2, 6, 10, 14)) { gates[1][i] = true; velocities[1][i] = 0.6f }
        // HiHat: 8ths
        for (i in listOf(0, 2, 4, 6, 8, 10, 12, 14)) { gates[2][i] = true; velocities[2][i] = 0.5f }
        // Bass: syncopated
        for (i in listOf(0, 3, 6, 10, 13)) { gates[3][i] = true; velocities[3][i] = 0.8f }
        // Keys: chords
        for (i in listOf(0, 8)) { gates[4][i] = true; velocities[4][i] = 0.5f }
        // Pad: sparse
        for (i in listOf(0, 8)) { gates[5][i] = true; velocities[5][i] = 0.4f }
        // Texture: scattered
        for (i in listOf(1, 5, 9, 11, 15)) { gates[6][i] = true; velocities[6][i] = 0.7f }
        // FX: accents
        for (i in listOf(3, 7, 11, 15)) { gates[7][i] = true; velocities[7][i] = 0.6f }

        PulsarStepGrid(
            vizData = remember {
                mutableStateOf(
                    PulsarVizData(
                        stepGates = gates,
                        stepVelocities = velocities,
                        playheads = intArrayOf(4, 4, 4, 4, 4, 4, 4, 4),
                        stepCounts = stepCounts,
                    ),
                )
            },
            energy = 0.7f,
            space = 0.4f,
            complexity = 0.5f,
            mood = 0.6f,
        )
    }
}

@Preview(name = "Idle (startup)", widthDp = 400, heightDp = 120)
@Preview(name = "Idle (startup) 140%", widthDp = 400, heightDp = 120, fontScale = 1.4f)
@Composable
private fun PulsarStepGridIdlePreview() {
    OrpheusTheme {
        // Default PulsarVizData — no gates, playheads at -1
        PulsarStepGrid(
            vizData = remember { mutableStateOf(PulsarVizData()) },
        )
    }
}
