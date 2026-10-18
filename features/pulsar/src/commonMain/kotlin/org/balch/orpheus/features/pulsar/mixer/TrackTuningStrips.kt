package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PULSAR_TRACK_NAMES
import org.balch.orpheus.features.pulsar.PulsarTrackColors
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.exp
import kotlin.math.roundToInt

// Unity on a 0..1.5 level fader sits two thirds of the way up.
private const val TRACK_UNITY_TRAVEL = TrackTuning.UNITY / TrackTuning.MAX_LEVEL
private val UnlitChip = Color(0xFF15101A)
private val STRIP_WIDTH = 42.dp

/** M/S chip size, also the gap a mute-less BAND strip keeps so its label stays in line. */
internal val STRIP_TOGGLE_SIZE = 18.dp

/**
 * Holds a fader of [full] dp, or in a [flexible] strip whatever height is left in the slot
 * (never under [min]). The siblings above and below are measured first, so they never clip.
 */
@Composable
internal fun ColumnScope.FlexibleFader(flexible: Boolean, full: Int, min: Int, fader: @Composable (Int) -> Unit) {
    if (!flexible) {
        fader(full)
        return
    }
    BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
        fader(maxHeight.value.toInt().coerceIn(min, full))
    }
}

/** BAND | TRACKS switch at the top of the Mix panel in tuning builds, with RESET in TRACKS or once the mix is touched. */
@Composable
internal fun TuningViewToggle(
    tracksView: Boolean,
    onTracksViewChange: (Boolean) -> Unit,
    resetEnabled: Boolean,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = OrpheusColors.mixerMasterPurple
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
        ) {
            ToggleSegment("BAND", selected = !tracksView, accent = accent) { onTracksViewChange(false) }
            ToggleSegment("TRACKS", selected = tracksView, accent = accent) { onTracksViewChange(true) }
        }
        Spacer(Modifier.weight(1f))
        // Always on TRACKS; on BAND only while there is something to reset, so edits never hide.
        if (tracksView || resetEnabled) {
            val color = if (resetEnabled) OrpheusColors.mixerFxAmber else OrpheusColors.seahawksGrey.copy(alpha = 0.5f)
            Text(
                text = "RESET",
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .focusRing(RoundedCornerShape(6.dp))
                    .clickable(enabled = resetEnabled, role = Role.Button, onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ToggleSegment(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.Black else accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(if (selected) accent else Color.Transparent)
            .focusRing(RoundedCornerShape(6.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Eight per-track strips: M/S, name, and a 0..1.5 level fader with a live meter. With [flexible]
 * the fader gives up height first when the slot is short; otherwise it keeps its full height.
 */
@Composable
internal fun TrackTuningStrips(
    uiState: MixerUiState,
    actions: MixerPanelActions,
    trackVizFlows: List<StateFlow<FloatArray>>,
    playing: () -> Boolean,
    flexible: Boolean = false,
) {
    val levels = remember { Array(TrackTuning.TRACK_COUNT) { mutableFloatStateOf(0f) } }
    TrackMeterDecayEffect(trackVizFlows, levels, playing)
    // Read inside each fader's draw/layer phase, so a moving meter never recomposes the strips.
    val meters = remember(levels) {
        Array(TrackTuning.TRACK_COUNT) { t -> { levelToDisplayFraction(levels[t].floatValue) } }
    }

    // Fits a 360dp cover screen; a narrower one scrolls rather than squeezing the faders.
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val tuning = uiState.tuning
            for (t in 0 until TrackTuning.TRACK_COUNT) {
                TrackStrip(
                    track = t,
                    muted = tuning.muted[t],
                    soloed = tuning.soloed[t],
                    level = tuning.levels[t],
                    silent = tuning.gain(t) == 0f,
                    meter = meters[t],
                    actions = actions,
                    flexible = flexible,
                )
            }
        }
    }
}

@Composable
private fun TrackStrip(
    track: Int,
    muted: Boolean,
    soloed: Boolean,
    level: Float,
    silent: Boolean,
    meter: () -> Float,
    actions: MixerPanelActions,
    flexible: Boolean,
) {
    val color = PulsarTrackColors[track]
    val who = PULSAR_TRACK_NAMES[track]
    Column(
        modifier = Modifier.width(STRIP_WIDTH).semantics { contentDescription = "Strip $who" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            StripToggle("M", "Mute $who", muted, OrpheusColors.mixerDistRed) {
                actions.toggleTrackMute(track)
            }
            StripToggle("S", "Solo $who", soloed, OrpheusColors.mixerFxAmber) {
                actions.toggleTrackSolo(track)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = who,
            color = color.copy(alpha = if (silent) 0.4f else 1f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        // The fader takes whatever height the chips and name leave, up to its full height.
        FlexibleFader(flexible, FULL_FADER_HEIGHT, MIN_FADER_HEIGHT) { height ->
            MixerFader(
                value = level / TrackTuning.MAX_LEVEL,
                onValueChange = { actions.setTrackLevel(track, it * TrackTuning.MAX_LEVEL) },
                accentColor = color,
                meterLevel = 0f,
                glowIntensity = 0f,
                meterProvider = meter,
                semanticsLabel = "Level $who",
                semanticsRange = 0f..TrackTuning.MAX_LEVEL,
                stepsLandOnUnity = true,
                dimmed = silent,
                trackHeight = height,
                trackWidth = 12,
                thumbWidth = 26,
                thumbHeight = 12,
                ledCount = ledCountFor(height),
                hotZoneCount = 3,
                unityTravel = TRACK_UNITY_TRAVEL,
                glass = false,
                onDoubleTap = { actions.setTrackLevel(track, TrackTuning.UNITY) },
            )
        }
        Text(
            text = ((level * 100f).roundToInt() / 100.0).toString(),
            color = if (level == TrackTuning.UNITY) OrpheusColors.synthGreen else OrpheusColors.mixerFxAmber,
            fontSize = 9.sp,
        )
    }
}

/** M or S chip: a switch that announces "<description>, on/off" and shows a ring under D-pad focus. */
@Composable
internal fun StripToggle(label: String, description: String, active: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(STRIP_TOGGLE_SIZE)
            .clip(shape)
            .background(if (active) color else UnlitChip)
            .border(1.dp, color.copy(alpha = if (active) 1f else 0.5f), shape)
            .focusRing(shape)
            .semantics {
                contentDescription = description
                stateDescription = if (active) "on" else "off"
            }
            .toggleable(value = active, role = Role.Switch, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) Color.Black else color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** A white ring while this control holds focus, so a D-pad user can see where they are. */
@Composable
private fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
}

/** Same attack/release shape as the bus meters, one level per track. */
@Composable
private fun TrackMeterDecayEffect(
    trackVizFlows: List<StateFlow<FloatArray>>,
    levels: Array<MutableFloatState>,
    playing: () -> Boolean,
) {
    LaunchedEffect(trackVizFlows) {
        var prevNanos = 0L
        while (true) {
            val nanos = withFrameNanos { it }
            if (prevNanos == 0L) {
                prevNanos = nanos
                continue
            }
            val dt = ((nanos - prevNanos) / 1_000_000_000f).coerceAtMost(0.1f)
            prevNanos = nanos
            val isPlaying = playing()
            val decay = exp(-(if (isPlaying) BAND_RELEASE_RATE else STOPPED_RELEASE_RATE) * dt)
            val attack = (ATTACK_RATE * dt).coerceAtMost(1f)
            for (t in levels.indices) {
                val state = levels[t]
                val current = state.floatValue
                val peak = if (isPlaying) bufferPeak(trackVizFlows.getOrNull(t)?.value ?: EMPTY_FLOAT_ARRAY) else 0f
                val next = if (peak > current) current + (peak - current) * attack else current * decay
                state.floatValue = if (next < 0.001f) 0f else next
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 300)
@Composable
private fun TrackTuningStripsPreview() {
    OrpheusTheme {
        MixerPanel(
            feature = MixerViewModel.previewFeature(
                MixerUiState(
                    tuning = TrackTuning().toggleSolo(3).toggleSolo(4).toggleMute(4).withLevel(3, 1.3f),
                )
            ),
            trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) },
            showTuning = true,
        )
    }
}
