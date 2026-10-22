package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS
import org.balch.orpheus.core.plugin.viz.VizChannel
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.Visualization
import kotlin.time.TimeSource

/**
 * Signal Monitor — master oscilloscope showing all viz channels.
 * Glowing traces on a dark background, low frost for maximum clarity. While Pulsar plays, its
 * eight tracks take a lane each across the stage, in place of the bus traces.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class SignalMonitorViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "signal-monitor"
    override val name = "Orphoscope"
    override val color = OrpheusColors.neonCyan
    override val knob1Label = "ZOOM"
    override val knob2Label = "GLOW"

    // Galaxy's default glass, which tones the lanes down under the panels; only the cyan title is ours.
    override val liquidEffects = VisualizationLiquidEffects(
        title = CenterPanelStyle(
            titleColor = OrpheusColors.neonCyan,
            borderColor = OrpheusColors.neonCyan.copy(alpha = 0.3f),
            titleElevation = 4.dp
        )
    )

    private var _zoom = 0.5f
    private var _glow = 0.5f

    override fun setKnob1(value: Float) { _zoom = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _glow = value.coerceIn(0f, 1f) }
    override fun onActivate() {}
    override fun onDeactivate() {}

    private data class Channel(
        val name: String,
        val color: Color,
        val id: Int,
    )

    private val channels = listOf(
        Channel("LFO", OrpheusColors.neonCyan, VizChannel.LFO),
        Channel("W-CARRIER", OrpheusColors.warpsGreen.copy(alpha = 0.5f), VizChannel.WARPS_CARRIER),
        Channel("W-MOD", OrpheusColors.warpsGreen.copy(alpha = 0.7f), VizChannel.WARPS_MOD),
        Channel("W-OUT", OrpheusColors.warpsGreen, VizChannel.WARPS_OUT),
        Channel("DLY-IN", OrpheusColors.warmGlow.copy(alpha = 0.6f), VizChannel.DELAY_IN),
        Channel("DLY-FB", OrpheusColors.warmGlow.copy(alpha = 0.4f), VizChannel.DELAY_FB),
        Channel("DLY-OUT", OrpheusColors.warmGlow, VizChannel.DELAY_OUT),
        Channel("REV-IN", OrpheusColors.echoPeriwinkle, VizChannel.REVERB_IN),
        Channel("REV-OUT", OrpheusColors.echoLavender, VizChannel.REVERB_OUT),
        Channel("FLUX", OrpheusColors.metallicBlueLight, VizChannel.FLUX_CV),
        Channel("RESO-IN", OrpheusColors.lakersGold.copy(alpha = 0.5f), VizChannel.RESO_IN),
        Channel("RESO-OUT", OrpheusColors.lakersGold, VizChannel.RESO_OUT),
        Channel("DRUM", OrpheusColors.ninersRed, VizChannel.DRUM_OUT),
        Channel("GRN-IN", OrpheusColors.grainsRed.copy(alpha = 0.5f), VizChannel.GRAINS_IN),
        Channel("GRN-OUT", OrpheusColors.grainsRed, VizChannel.GRAINS_OUT),
        Channel("BASS", OrpheusColors.bassAmber, VizChannel.BASS_OUT),
        Channel("HORN-IN", OrpheusColors.hornWoofer, VizChannel.HORN_IN),
        Channel("HORN-OUT", OrpheusColors.hornCrimson, VizChannel.HORN_OUT),
        Channel("DJ", OrpheusColors.djRed, VizChannel.DJ_OUT),
        Channel("MASTER", OrpheusColors.neonMagenta, VizChannel.MASTER_OUT),
        Channel("SYZ-0", OrpheusColors.neonOrange, VizChannel.TIDES_CH0),
        Channel("SYZ-1", OrpheusColors.warmGlow, VizChannel.TIDES_CH1),
        Channel("SYZ-2", OrpheusColors.neonCyan.copy(alpha = 0.7f), VizChannel.TIDES_CH2),
        Channel("SYZ-3", OrpheusColors.neonMagenta.copy(alpha = 0.7f), VizChannel.TIDES_CH3),
    )

    @Composable
    override fun Content(modifier: Modifier) {
        val trackHistory = engine.pulsarTrackHistory
        val busHistory = engine.scopeHistory

        // Redrawn every display frame while started: the traces scroll by time rather than by
        // poll, so they glide between the polls' uneven batches. Read only in draw, so nothing
        // recomposes.
        val frame = remember { mutableLongStateOf(0L) }
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) withFrameNanos { frame.longValue = it }
            }
        }

        // The stage is in root coordinates and this layer is normally at the origin, but the
        // offset is subtracted rather than assumed to be zero.
        var origin by remember { mutableStateOf(Offset.Zero) }
        val stage = LocalVizStage.current
        val lanes = remember { OrphoscopeLanes(OrpheusColors.pulsarTracks) }
        val trackRings = remember { Array(PULSAR_NUM_TRACKS) { FloatArray(0) } }
        val trackEnds = remember { IntArray(PULSAR_NUM_TRACKS) }
        val laneClock = remember { ScrollClock(LANE_LAG_SECONDS) }
        val busClock = remember { ScrollClock(BUS_LAG_SECONDS) }
        // The pumps run on their own clock: each draw moves them to now, so the swell is smooth.
        val pump = remember { LanePump(PULSAR_NUM_TRACKS) }
        val clock = remember { TimeSource.Monotonic.markNow() }
        val paths = remember { Array(channels.size) { Path() } }
        val haloStroke = remember { StrokeCache() }
        val traceStroke = remember { StrokeCache() }

        Canvas(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
            frame.longValue
            val now = TimeSource.Monotonic.markNow()
            val w = size.width
            val h = size.height
            val margin = 24f
            // All bus traces share one axis across the full height.
            val mid = h / 2f
            val amplitude = h * 0.4f
            val traceLeft = margin
            val traceRight = w - margin

            // Dark background
            drawRect(OrpheusColors.fireworksBackground)

            // Zoom: 0 = full 5sec history, 1 = last ~50 samples (waveform detail). A few samples
            // short of the ring, so the clock's lag never runs the window off its oldest end.
            val visibleSamples = (VISIBLE_MAX * (1f - _zoom * 0.9f)).toInt().coerceAtLeast(30)

            pump.update(clock.elapsedNow().inWholeNanoseconds / 1e9, engine.pulsarVizFlow.value)
            val tracks = trackHistory.value
            if (tracks != null) {
                val head = laneClock.position(tracks.ends[VizChannel.PULSAR_TRACK_0], tracks.arrivedAt, now)
                for (t in 0 until PULSAR_NUM_TRACKS) {
                    trackRings[t] = tracks.samples[VizChannel.PULSAR_TRACK_0 + t]
                    trackEnds[t] = laneClock.rel(tracks.ends[VizChannel.PULSAR_TRACK_0 + t])
                }
                // While Pulsar plays, its tracks are the whole picture: eight lanes over the stage,
                // and the bus traces (delay, reverb, master and the rest) step aside.
                if (lanes.anyLive(trackRings, trackEnds, head, visibleSamples)) {
                    // Between the header and the nav where a host reports a stage, else the window.
                    val stageLocal = stage?.bounds?.translate(-origin.x, -origin.y)
                        ?.intersect(Rect(0f, 0f, w, h))
                        ?.takeIf { it.width > 0f && it.height > 0f }
                        ?: Rect(0f, 0f, w, h)
                    val inset = stageLocal.height * 0.04f
                    val area = Rect(
                        stageLocal.left + margin, stageLocal.top + inset,
                        stageLocal.right - margin, stageLocal.bottom - inset,
                    )
                    lanes.draw(this, trackRings, trackEnds, head, visibleSamples, area, _glow, pump.level)
                    return@Canvas
                }
            }
            // Center reference line
            drawLine(
                color = Color.White.copy(alpha = 0.04f),
                start = Offset(margin, mid),
                end = Offset(w - margin, mid),
                strokeWidth = 0.5f
            )
            val bus = busHistory.value ?: return@Canvas
            val busHead = busClock.position(bus.ends[VizChannel.MASTER_OUT], bus.arrivedAt, now)
            val stride = scrollStride(visibleSamples)
            val buckets = scrollBuckets(busHead, visibleSamples, stride)
            val perSample = (traceRight - traceLeft) / visibleSamples
            val firstX = traceRight - (busHead - (buckets.first * stride + stride / 2.0)).toFloat() * perSample
            val from = kotlin.math.floor(busHead).toInt() - visibleSamples

            // Draw each channel overlaid at center — skip empty (bypassed) channels.
            // Two stroke passes (glow halo + crisp trace) keep this full-screen scope cheap to
            // record: the old 4-pass + array-copy path dominated UI-thread draw time (see
            // gfxinfo "slow issue draw commands"). The traces scroll like the lanes: buckets
            // fixed to the samples, placed by time.
            clipRect(traceLeft, 0f, traceRight, h) {
                channels.forEachIndexed { idx, channel ->
                    val ring = bus.samples[channel.id]
                    if (ring.isEmpty()) return@forEachIndexed
                    val end = busClock.rel(bus.ends[channel.id])

                    // Skip silent (bypassed) channels — flat lines add cost, not signal.
                    var peak = 0f
                    for (i in maxOf(0, from - (end - ring.size)) until ring.size) {
                        val a = kotlin.math.abs(ring[i])
                        if (a > peak) peak = a
                    }
                    if (peak < 0.001f) return@forEachIndexed

                    val path = paths[idx]
                    path.reset()
                    var p = 0
                    for (b in buckets) {
                        val x = firstX + p * stride * perSample
                        val y = mid - bucketPeak(ring, end, b, stride, signed = true).coerceIn(-1f, 1f) * amplitude
                        if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        p++
                    }

                    // Pass 1 — wide glow halo.
                    drawPath(
                        path,
                        channel.color.copy(alpha = 0.04f + _glow * 0.12f),
                        style = haloStroke.of(HALO_WIDTH.toPx()),
                    )
                    // Pass 2 — crisp main trace.
                    drawPath(
                        path,
                        channel.color.copy(alpha = 0.30f + _glow * 0.45f),
                        style = traceStroke.of(TRACE_WIDTH.toPx()),
                    )
                }
            }
        }
    }

    companion object {
        // The rings hold 480 samples; the window stays a little short so the clock's lag and a
        // bucket of slack never reach past the oldest.
        private const val VISIBLE_MAX = 460

        // A poll and a half behind the newest sample: the Pulsar poll runs at 60 Hz, the bus at 30.
        private const val LANE_LAG_SECONDS = 0.025
        private const val BUS_LAG_SECONDS = 0.05

        // In dp, so a 2x tablet gets lines as fat as a 1x desktop's.
        private val HALO_WIDTH = 5.dp
        private val TRACE_WIDTH = 2.dp
    }
}
