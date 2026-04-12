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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.random.Random

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
private const val MAX_PARTICLES = 50
private const val SIGNAL_HISTORY_SIZE = 128  // ~2 seconds at 60fps

private class GridParticle {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var radius = 0f; var color: Color = Color.Transparent
    var life = 0f; var decay = 2f; var active = false
}

private class GridParticlePool {
    val particles = Array(MAX_PARTICLES) { GridParticle() }
    private var nextIndex = 0

    fun spawn(x: Float, y: Float, color: Color, velocity: Float, energy: Float) {
        val count = (energy * 4).toInt().coerceIn(1, 4)
        repeat(count) {
            val p = particles[nextIndex % MAX_PARTICLES]
            nextIndex++
            p.x = x; p.y = y
            p.vx = (Random.nextFloat() - 0.5f) * 30f
            p.vy = -(20f + Random.nextFloat() * 40f)
            p.radius = 2f + velocity * 3f
            p.color = color; p.life = 1f
            p.decay = 1.8f + Random.nextFloat() * 0.4f
            p.active = true
        }
    }

    fun update(dt: Float) {
        for (p in particles) {
            if (!p.active) continue
            p.x += p.vx * dt; p.y += p.vy * dt
            p.life -= p.decay * dt
            if (p.life <= 0f) { p.active = false; p.life = 0f }
        }
    }
}

/**
 * Canvas-drawn step grid visualization showing 8 tracks x up to 32 steps.
 * Features glowing cells, playhead afterglow trail, energy particles,
 * waveform traces, beat pulse, and liquid glass overlay.
 */
@Composable
fun PulsarStepGrid(
    vizData: PulsarVizData,
    trackVizFlows: List<StateFlow<FloatArray>> = List(NUM_TRACKS) { MutableStateFlow(FloatArray(0)) },
    energy: Float = 0.5f,
    space: Float = 0.4f,
    complexity: Float = 0.3f,
    mood: Float = 0.5f,
    selectedTrack: Int? = null,
    onTrackSelected: (Int?) -> Unit = {},
    arrangementState: PulsarArrangementState? = null,
    arrangement: Arrangement? = null,
    modifier: Modifier = Modifier,
) {
    val cellGap = 2f
    val trackGap = 4f

    val particlePool = remember { GridParticlePool() }
    var wavePhase by remember { mutableFloatStateOf(0f) }
    var beatPulse by remember { mutableFloatStateOf(1f) }
    val prevPlayheads = remember { IntArray(NUM_TRACKS) { -1 } }
    val smoothedLevels = remember { FloatArray(NUM_TRACKS) }
    // Running peak per track for stable waveform normalization (slow decay)
    val trackPeaks = remember { FloatArray(NUM_TRACKS) { 0.1f } }
    // Collect per-track waveform data from viz flows
    val trackWaveforms = Array(NUM_TRACKS) { t ->
        trackVizFlows[t].collectAsState().value
    }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0.016f
                else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                lastNanos = nanos
                particlePool.update(dt)
                wavePhase += dt * 2f
                if (beatPulse > 1f) beatPulse = (beatPulse - dt * 15f).coerceAtLeast(1f)

                // Smooth track levels: fast attack (~10ms), slow decay (~300ms)
                for (t in 0 until NUM_TRACKS) {
                    val raw = vizData.trackLevels[t].coerceIn(0f, 1f)
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

                for (t in 0 until NUM_TRACKS) {
                    val ph = vizData.playheads[t]
                    if (ph != prevPlayheads[t] && ph >= 0) {
                        prevPlayheads[t] = ph
                        if (t == 0) beatPulse = 1.03f
                        if (ph < vizData.stepCounts[t] &&
                            vizData.stepGates[t][ph] &&
                            vizData.stepVelocities[t][ph] > 0.5f) {
                            val steps = vizData.stepCounts[t].coerceIn(1, MAX_STEPS)
                            val cellW = 360f / steps
                            val trackH = 100f / NUM_TRACKS
                            val cx = ph * cellW + cellW / 2
                            val cy = t * trackH + trackH / 2
                            particlePool.spawn(cx, cy, TrackColors[t],
                                vizData.stepVelocities[t][ph], energy)
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
            .graphicsLayer {
                scaleX = beatPulse
                scaleY = beatPulse
            }
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

        // Layer 0: Canvas draws cells, glow, particles
        Canvas(
            modifier = Modifier.matchParentSize()
                .liquefiable(gridLiquidState)
        ) {
            val totalTrackGaps = (NUM_TRACKS - 1) * trackGap
            val trackHeight = (size.height - totalTrackGaps) / NUM_TRACKS

            // Layer 2-5: Cells, glow, playhead, selection
            for (track in 0 until NUM_TRACKS) {
                val rawSteps = vizData.stepCounts[track]
                if (rawSteps < 2) continue  // no data yet — skip track
                val steps = rawSteps.coerceAtMost(MAX_STEPS)
                val trackY = track * (trackHeight + trackGap)
                val totalCellGaps = (steps - 1) * cellGap
                val cellWidth = (size.width - totalCellGaps) / steps
                val color = TrackColors[track]
                val playhead = vizData.playheads[track]

                // Selection highlight border
                if (track == selectedTrack) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.3f),
                        topLeft = Offset(-2f, trackY - 2f),
                        size = Size(size.width + 4f, trackHeight + 4f),
                        cornerRadius = CornerRadius(4f, 4f),
                        style = Stroke(width = 1.5f),
                    )
                }

                for (step in 0 until steps) {
                    val cellX = step * (cellWidth + cellGap)
                    val gate = vizData.stepGates[track][step]
                    val velocity = vizData.stepVelocities[track][step].coerceIn(0f, 1f)
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

                    // Active cell — raised embossed square
                    if (gate) {
                        val cx = cellX + cellWidth / 2
                        val cy = trackY + trackHeight / 2
                        val baseAlpha = if (isAtPlayhead) {
                            (0.4f + trackLevel * 0.6f).coerceAtMost(1f)
                        } else {
                            velocity * 0.7f
                        }
                        val pulse = if (isAtPlayhead) 1f + trackLevel * 0.1f else 1f
                        val blobW = cellWidth * pulse
                        val blobH = trackHeight * pulse
                        val blobX = cx - blobW / 2
                        val blobY = cy - blobH / 2

                        // Glow halo
                        if (isAtPlayhead && trackLevel > 0.02f) {
                            val glowPad = 2f + trackLevel * 5f
                            drawRoundRect(
                                color = color.copy(alpha = trackLevel * 0.4f),
                                topLeft = Offset(blobX - glowPad, blobY - glowPad),
                                size = Size(blobW + glowPad * 2, blobH + glowPad * 2),
                                cornerRadius = CornerRadius(4f, 4f),
                                blendMode = BlendMode.Plus,
                            )
                        }

                        // Solid fill
                        drawRoundRect(
                            color = color.copy(alpha = baseAlpha),
                            topLeft = Offset(blobX, blobY),
                            size = Size(blobW, blobH),
                            cornerRadius = corner,
                        )

                        // Top-left highlight
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0f to Color.White.copy(alpha = baseAlpha * 0.3f),
                                    0.3f to Color.Transparent,
                                ),
                                start = Offset(blobX, blobY),
                                end = Offset(blobX + blobW, blobY + blobH),
                            ),
                            topLeft = Offset(blobX, blobY),
                            size = Size(blobW, blobH),
                            cornerRadius = corner,
                        )

                        // Bottom-right shadow — depth
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.7f to Color.Black.copy(alpha = baseAlpha * 0.15f),
                                    1f to Color.Black.copy(alpha = baseAlpha * 0.3f),
                                ),
                                start = Offset(blobX, blobY),
                                end = Offset(blobX + blobW, blobY + blobH),
                            ),
                            topLeft = Offset(blobX, blobY),
                            size = Size(blobW, blobH),
                            cornerRadius = corner,
                        )

                        // Border
                        drawRoundRect(
                            color = color.copy(alpha = baseAlpha * 0.5f),
                            topLeft = Offset(blobX, blobY),
                            size = Size(blobW, blobH),
                            cornerRadius = corner,
                            style = Stroke(width = 1f),
                        )
                    }

                }
            }

            // Layer 6: Particles
            for (p in particlePool.particles) {
                if (!p.active) continue
                val r = p.radius * (0.5f + 0.5f * p.life)
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to p.color.copy(alpha = p.life),
                            0.5f to p.color.copy(alpha = p.life * 0.5f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(p.x, p.y),
                        radius = r,
                    ),
                    center = Offset(p.x, p.y),
                    radius = r,
                    blendMode = BlendMode.Plus,
                )
            }

            // (signal traces drawn ON TOP of glass — see overlay Canvas below)
        }

        // Layer 7: Liquid glass overlay — only when playing
        val isPlaying = vizData.playheads[0] >= 0
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
        if (isPlaying && vizData.stepCounts[0] >= 2) {
            val steps = vizData.stepCounts[0].coerceAtMost(MAX_STEPS)
            val phPos = vizData.playheads[0].coerceIn(0, steps - 1)
            val totalCellGaps = (steps - 1) * cellGap
            val gridWidthDp = maxWidth.value  // actual rendered width from BoxWithConstraints
            val cellW = (gridWidthDp - totalCellGaps) / steps
            val phX = phPos * (cellW + cellGap)

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
                    .offset(x = phX.dp)
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
            val totalTrackGaps = (NUM_TRACKS - 1) * trackGap
            val trackHeight = (size.height - totalTrackGaps) / NUM_TRACKS
            val boldness = 1f + frost * 2f

            for (track in 0 until NUM_TRACKS) {
                if (vizData.stepCounts[track] < 2) continue
                val data = trackWaveforms[track]
                if (data.isEmpty()) continue

                val trackY = track * (trackHeight + trackGap)
                val color = TrackColors[track]
                // Anchor at 85% down (15% from bottom), peaks reach 7% from top
                val baseY = trackY + trackHeight * 0.85f
                val maxH = trackHeight * 0.78f  // 85% - 7% = 78% range

                // Use running peak for stable normalization (not per-frame max)
                val maxVal = trackPeaks[track].coerceAtLeast(0.01f)

                val path = Path()
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
                    style = Stroke(width = 4f),
                    blendMode = BlendMode.Plus,
                )
                // Main trace
                drawPath(
                    path = path,
                    color = color.copy(alpha = (0.25f * boldness).coerceAtMost(0.6f)),
                    style = Stroke(width = 1.5f),
                )
            }
        }

        // Layer 9: Arrangement state overlay

        val sectionName = arrangementState?.sectionIndex?.let { sectionIndex ->
            arrangement?.sections?.getOrNull(sectionIndex)?.name
        }

        if (sectionName != null) {
            val currentBar = arrangementState.barsElapsed + 1
            val barText = "$currentBar/${arrangementState.barsTotal}"
            val soloText = if (arrangementState.soloActive && arrangementState.soloTrack >= 0) {
                val name = if (arrangementState.bandSolo) {
                    arrangementState.bandMemberNames.getOrElse(arrangementState.soloTrack) { "?" }
                } else {
                    PULSAR_TRACK_NAMES.getOrElse(arrangementState.soloTrack) { "?" }
                }
                " \u25b8 $name"
            } else ""

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
                    text = "$sectionName $barText$soloText",
                    color = Color.White.copy(alpha = 0.9f),
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
@Composable
private fun PulsarStepGridPreview() {
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
        vizData = PulsarVizData(
            stepGates = gates,
            stepVelocities = velocities,
            playheads = intArrayOf(4, 4, 4, 4, 4, 4, 4, 4),
            stepCounts = stepCounts,
        ),
        energy = 0.7f,
        space = 0.4f,
        complexity = 0.5f,
        mood = 0.6f,
    )
}

@Preview(name = "Idle (startup)", widthDp = 400, heightDp = 120)
@Composable
private fun PulsarStepGridIdlePreview() {
    // Default PulsarVizData — no gates, playheads at -1
    PulsarStepGrid(
        vizData = PulsarVizData(),
    )
}
