package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// Time-based exponential decay rates (per-second). exp(-rate * dt) gives the
// per-frame multiplier; rate ≈ ln(10) / time_to_10_percent_seconds. The C++ viz
// ring already gives us ~640ms of peak history; band meters need a snappy
// release so transients feel "alive" without strobing to 0 between sparse hits.
private const val BAND_RELEASE_RATE = 18f     // band meters: ~130ms to 10% — punchy
private const val PEAK_RELEASE_RATE = 12f     // ~190ms to 10% — smooths the 5Hz peak source
private const val STOPPED_RELEASE_RATE = 35f  // not playing: ~65ms to silent — snap to 0
// Attack rate (per-second), scaled by dt and clamped to 1.0. At 250/s, even a
// 120Hz refresh (dt ≈ 8ms) produces attack = 1.0, so transients hit the new
// peak in a single frame at any practical display rate.
private const val ATTACK_RATE = 250f
// Band meter floor sits 12 dB below the DIST peak floor: vibes with sparse or
// quiet auxiliary tracks (low track volume × naturally quiet engine output)
// land around -25 to -32 dB post-volume, which would read dead at -24. -36 dB
// keeps loud hits pinned at the top while quiet pads still register a few
// LEDs. DIST keeps the tighter -24 floor — its post-saturation signal sits in
// the comfortable range already and the tighter scale makes drive easier to read.
private const val METER_FLOOR_DB = -36f
private const val PEAK_METER_FLOOR_DB = -24f

private data class GroupAccent(
    val group: MixerGroup,
    val label: String,
    val color: Color,
)

private val GROUP_ACCENTS = listOf(
    GroupAccent(MixerGroup.PERC, "PERC", OrpheusColors.mixerPercPink),
    GroupAccent(MixerGroup.BASS, "BASS", OrpheusColors.neonCyan),
    GroupAccent(MixerGroup.KEYS, "KEYS", OrpheusColors.synthGreen),
    GroupAccent(MixerGroup.FX,   "FX",   OrpheusColors.mixerFxAmber),
)
private const val GROUP_COUNT = 4

// Hoisted to avoid per-frame allocation on the elvis fallback in the meter loop.
private val EMPTY_FLOAT_ARRAY = FloatArray(0)

@Composable
fun MixerPanel(
    feature: MixerFeature = MixerViewModel.feature(),
    trackVizFlows: List<StateFlow<FloatArray>> = emptyList(),
    masterOutVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    // Per-group decayed peak levels stored as 4 individual MutableFloatStates
    // bundled into an Array. Avoids the per-set Float boxing that
    // SnapshotStateList<Float> incurs (~240 box allocations/sec at 60fps).
    // Index aligns with GROUP_ACCENTS.
    val decayedLevels = remember {
        arrayOf(
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
            mutableFloatStateOf(0f),
        )
    }
    // Smoothed master/DIST peak — engine peakFlow updates only at ~5Hz, so we
    // snap up on rising edges and exponentially decay on falling so the meter
    // reads as continuous motion rather than dipping to 0 between updates.
    val smoothedPeak = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(trackVizFlows) {
        // Frame-clocked meter loop. withFrameNanos suspends until the Compose
        // choreographer requests a frame, so we only update when the screen is
        // actually redrawing. Decay is computed from real dt, making the response
        // independent of the display refresh rate.
        //
        // Allocation-free hot path:
        //   * EMPTY_FLOAT_ARRAY is hoisted, no per-frame elvis allocation
        //   * Manual int-counter loops avoid IntRange/Iterator allocations
        //   * MutableFloatState (vs SnapshotStateList<Float>) avoids Float boxing
        //   * Manual peak loop over the IntArray avoids maxOfOrNull's Float? box
        var prevNanos = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            if (prevNanos == 0L) {
                prevNanos = nanos
                continue
            }
            // Clamp dt to handle long pauses (background, breakpoints) so we don't
            // suddenly snap meters to 0 after resuming.
            val dt = ((nanos - prevNanos) / 1_000_000_000f).coerceAtMost(0.1f)
            prevNanos = nanos

            // Read the latest playing state directly from the StateFlow so we don't
            // need to re-key the LaunchedEffect when it toggles.
            val playing = feature.stateFlow.value.playing
            if (!playing) {
                // C++ viz ring buffer holds its last samples after stop; if we kept
                // running peak detection the meter would pin to the last loud frame.
                // Force a fast decay (~65ms to silent) so meters fall to 0 visibly.
                val decayMul = exp(-STOPPED_RELEASE_RATE * dt)
                for (i in 0 until GROUP_COUNT) {
                    val state = decayedLevels[i]
                    val faded = state.floatValue * decayMul
                    state.floatValue = if (faded < 0.001f) 0f else faded
                }
                val faded = smoothedPeak.floatValue * decayMul
                smoothedPeak.floatValue = if (faded < 0.001f) 0f else faded
            } else {
                val bandDecayMul = exp(-BAND_RELEASE_RATE * dt)
                val attack = (ATTACK_RATE * dt).coerceAtMost(1f)
                for (idx in 0 until GROUP_COUNT) {
                    val accent = GROUP_ACCENTS[idx]
                    val tracks = accent.group.tracks
                    // Manual max: maxOfOrNull on IntArray boxes the Float? return.
                    var groupPeak = 0f
                    for (j in tracks.indices) {
                        val flow = trackVizFlows.getOrNull(tracks[j])
                        val buf = flow?.value ?: EMPTY_FLOAT_ARRAY
                        val p = bufferPeak(buf)
                        if (p > groupPeak) groupPeak = p
                    }
                    val state = decayedLevels[idx]
                    val current = state.floatValue
                    state.floatValue = if (groupPeak > current) {
                        current + (groupPeak - current) * attack
                    } else {
                        current * bandDecayMul
                    }
                }
                // DIST/master peak: snap up on rising edges (so transients hit
                // immediately) and decay exponentially on falling. Bridges the
                // 5Hz update cadence of the engine peakFlow into a smooth bounce.
                val rawPeak = feature.stateFlow.value.peak
                val curPeak = smoothedPeak.floatValue
                smoothedPeak.floatValue = if (rawPeak > curPeak) {
                    rawPeak  // snap up
                } else {
                    val peakDecay = exp(-PEAK_RELEASE_RATE * dt)
                    curPeak * peakDecay
                }
            }
        }
    }

    CollapsibleColumnPanel(
        title = "MIX",
        color = OrpheusColors.mixerMasterPurple,
        expandedTitle = if (showExpandedTitle) "Mix Bridge" else null,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                // Stable per-band onGainChange lambdas — capturing only `actions`
                // and the band's MixerGroup constant. Without this, fresh lambdas
                // are allocated on every recomposition (4 per recomp), defeating
                // GroupStrip's ability to skip recompositions.
                val onPercChange = remember(actions) { { v: Float -> actions.setGroupGain(MixerGroup.PERC, v) } }
                val onBassChange = remember(actions) { { v: Float -> actions.setGroupGain(MixerGroup.BASS, v) } }
                val onKeysChange = remember(actions) { { v: Float -> actions.setGroupGain(MixerGroup.KEYS, v) } }
                val onFxChange   = remember(actions) { { v: Float -> actions.setGroupGain(MixerGroup.FX,   v) } }
                val onChange = arrayOf(onPercChange, onBassChange, onKeysChange, onFxChange)

                for (idx in 0 until GROUP_COUNT) {
                    val accent = GROUP_ACCENTS[idx]
                    val linearLevel = decayedLevels[idx].floatValue
                    val displayFraction = levelToDisplayFraction(linearLevel)
                    val gain = uiState.groupGains.getOrElse(idx) { 1f }
                    val muted = uiState.groupMuted.getOrElse(idx) { false }
                    GroupStrip(
                        accent = accent,
                        gain = gain,
                        meterLevel = displayFraction,
                        muted = muted,
                        onGainChange = onChange[idx],
                    )
                }
                DistStrip(
                    drive = uiState.drive,
                    peak = smoothedPeak.floatValue,
                    onDriveChange = actions.setDrive,
                )
            }
        }
    }
}


/**
 * Shared layout shell for every channel in the mixer (PERC/BASS/KEYS/FX + DIST).
 * Owns the pulse-dot → fader → label → value stack so the columns line up across
 * the strip; per-channel data (fader behavior, value formatting, color logic)
 * arrives via parameters and the [fader] slot.
 *
 * Arrangement.Top + explicit Spacers (instead of SpaceBetween) — the parent Row
 * uses Alignment.Bottom, which makes the Row's height = tallest child. With
 * SpaceBetween any extra slack the Row hands us was distributed as gaps between
 * every child, producing the dead space between label and value visible in the
 * production layout but not in the preview.
 */
@Composable
private fun FaderStrip(
    label: String,
    labelColor: Color,
    pulseColor: Color,
    valueText: String,
    valueColor: Color,
    fader: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(pulseColor),
        )
        Spacer(modifier = Modifier.height(6.dp))
        fader()
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(
            text = valueText,
            color = valueColor,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun GroupStrip(
    accent: GroupAccent,
    gain: Float,
    meterLevel: Float,
    muted: Boolean,
    onGainChange: (Float) -> Unit,
) {
    val multiplier = faderToGain(gain)
    val pulseAlpha = if (muted) 0.1f else (0.3f + meterLevel * 0.7f).coerceAtMost(1f)
    FaderStrip(
        label = accent.label,
        labelColor = accent.color.copy(alpha = if (muted) 0.4f else 1f),
        pulseColor = accent.color.copy(alpha = pulseAlpha),
        // Live amplitude multiplier (0.75 fader → "1.00x"). Color smoothly
        // transitions yellow → green → red across unity. Muted bands keep
        // the accent-tinted dim look.
        valueText = formatMultiplier(multiplier),
        valueColor = if (muted) accent.color.copy(alpha = 0.3f) else multiplierColor(multiplier),
        fader = {
            MixerFader(
                value = gain,
                onValueChange = onGainChange,
                accentColor = accent.color,
                meterLevel = if (muted) 0f else meterLevel,
                glowIntensity = if (muted) 0f else (meterLevel * gain).coerceIn(0f, 1f),
                dimmed = muted,
                unityTravel = UNITY_TRAVEL,
            )
        },
    )
}

@Composable
private fun DistStrip(
    drive: Float,
    peak: Float,
    onDriveChange: (Float) -> Unit,
) {
    val accent = OrpheusColors.seahawksGrey
    val peakFraction = peakToDisplayFraction(peak)
    FaderStrip(
        label = "GAIN",
        labelColor = accent,
        pulseColor = accent.copy(alpha = (0.3f + peakFraction * 0.7f).coerceAtMost(1f)),
        valueText = formatTwoDp(drive),
        valueColor = accent.copy(alpha = 0.55f),
        fader = {
            MixerFader(
                value = drive,
                onValueChange = onDriveChange,
                accentColor = accent,
                meterLevel = peakFraction,
                glowIntensity = (peakFraction * drive).coerceIn(0f, 1f),
                glassTint = OrpheusColors.seahawksGrey,
                peakStyle = true,
                glassTintAlpha = 0.20f,
            )
        },
    )
}

// The C++ viz ring stores ~640ms of per-track peaks (480 entries × ~1.3ms per
// audio block at 48kHz/64-frame blocks). Reading the entire ring shows "loudest
// peak in the last half second" — historical, not current. We want band meters
// to react to NOW, like DIST does, so we look at only the tail of the buffer.
//
// Each ring entry is a per-block peak, NOT a raw audio sample — 16 entries
// ≈ 16 × 1.3ms ≈ 21ms, which spans our ~16ms frame interval at 60Hz without
// missing peaks but is short enough that the meter snaps cleanly when a hit
// ends instead of holding the last drum's peak for hundreds of ms.
private const val PEAK_WINDOW_ENTRIES = 16

/** Peak (max abs entry) over the tail of the per-block ring buffer; returns 0 for empty buffers. */
private fun bufferPeak(buf: FloatArray, tailEntries: Int = PEAK_WINDOW_ENTRIES): Float {
    if (buf.isEmpty()) return 0f
    val start = (buf.size - tailEntries).coerceAtLeast(0)
    var peak = 0f
    for (i in start until buf.size) {
        val a = abs(buf[i])
        if (a > peak) peak = a
    }
    return peak
}

/**
 * Map a linear amplitude (0..1) to a 0..1 display fraction using dB scaling
 * against [METER_FLOOR_DB] (-36dB): linear=1 → 1.0, linear=0.5 → ~0.83,
 * linear=0.25 → ~0.67, linear=0.1 → ~0.44, linear=0.025 → ~0.11,
 * linear<=0.0158 (-36dB) → 0.
 */
private fun levelToDisplayFraction(linearLevel: Float): Float {
    if (linearLevel <= 0.0001f) return 0f
    val db = 20f * log10(linearLevel.coerceIn(0.0001f, 1f))
    return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
}

/**
 * Tighter dB mapping for the DIST peak meter. With a -24dB floor the typical
 * 0.14-0.33 operating range fills ~30-60% of the meter:
 *   peak=0.14 → ~-17dB → 0.29 (~5/18 LEDs)
 *   peak=0.33 → ~-9.6dB → 0.60 (~11/18 LEDs)
 *   peak=0.95 → ~-0.4dB → 0.98 (~17/18 LEDs)
 */
private fun peakToDisplayFraction(peakLinear: Float): Float {
    if (peakLinear <= 0.0001f) return 0f
    val db = 20f * log10(peakLinear.coerceIn(0.0001f, 1f))
    return ((db - PEAK_METER_FLOOR_DB) / -PEAK_METER_FLOOR_DB).coerceIn(0f, 1f)
}

private fun formatTwoDp(value: Float): String {
    val rounded = (value * 100f).roundToInt() / 100.0
    return rounded.toString()
}

/**
 * Console fader law — mirror of pulsar_fader_to_gain() in
 * orpheus_unit_pulsar.cpp. Maps fader *travel* (0..1) to amplitude gain
 * using a piecewise-linear-in-dB curve modeled on a Penny & Giles broadcast
 * fader (gentler than a Yamaha/Mackie law — 50% travel reads as half-loud,
 * not heavily-cut):
 *   travel 0.00 → 0×    (silent below 0.05)
 *   travel 0.05 → -40 dB (~0.01×)
 *   travel 0.50 → -10 dB (~0.32×)
 *   travel 0.75 →   0 dB (1.00× — unity)
 *   travel 1.00 →  +6 dB (~1.99×)
 *
 * Used both for the live multiplier readout under each band label and for
 * the unity-notch position on the fader track. Keep in sync with the C++.
 */
internal fun faderToGain(travel: Float): Float {
    if (travel <= 0.05f) return 0f
    val db = when {
        travel >= 0.75f -> (travel - 0.75f) * 24f
        travel >= 0.50f -> -10f + (travel - 0.50f) * 40f
        else            -> -40f + (travel - 0.05f) * (30f / 0.45f)
    }
    return 10f.pow(db / 20f)
}

/** Travel position of the unity (0 dB) point on the fader. Drives the notch. */
internal const val UNITY_TRAVEL = 0.75f

/** "1.00x" / "0.45x" / "2.50x" — readout under the band label. */
private fun formatMultiplier(gain: Float): String {
    val rounded = (gain * 100f).roundToInt() / 100.0
    return "${rounded}x"
}

/** Max gain at the top of the fader (travel = 1.0 → +6 dB ≈ 1.995×). */
private const val MAX_GAIN = 1.995f

/**
 * Smooth color for the multiplier readout: yellow when cut, green at unity,
 * red as it climbs above unity. Linear RGB lerp through three waypoints —
 * green is the "happy" middle so it pops visually as the user dials in.
 *   gain 0.00 → yellow (deeply cut / silent)
 *   gain 1.00 → green (unity)
 *   gain 1.99 → red (max boost, +6 dB)
 */
private fun multiplierColor(gain: Float): Color {
    val green = OrpheusColors.synthGreen
    val yellow = OrpheusColors.mixerFxAmber
    val red = OrpheusColors.mixerDistRed
    return when {
        gain <= 0f -> yellow
        gain < 1f -> lerp(yellow, green, gain.coerceIn(0f, 1f))
        else -> lerp(green, red, ((gain - 1f) / (MAX_GAIN - 1f)).coerceIn(0f, 1f))
    }
}

@Preview(widthDp = 480, heightDp = 320)
@Composable
fun MixerPanelPreview() {
    MixerPanel(
        feature = MixerViewModel.previewFeature(
            state = MixerUiState(
                groupGains = listOf(0.7f, 0.55f, 0.6f, 0.35f),
                drive = 0.62f,
                peak = 0.42f,
                groupMuted = listOf(false, false, false, false),
            )
        ),
        trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) },
    )
}

@Preview(widthDp = 480, heightDp = 320)
@Composable
fun MixerPanelMutedFxPreview() {
    MixerPanel(
        feature = MixerViewModel.previewFeature(
            state = MixerUiState(
                groupGains = listOf(0.7f, 0.55f, 0.6f, 0.35f),
                drive = 0.0f,
                peak = 0.12f,
                groupMuted = listOf(false, false, false, true),
            )
        ),
        trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) },
    )
}
