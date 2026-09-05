package org.balch.orpheus.djapp.vibeinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.OrpheusSlideUpSheet

/**
 * Bottom slide-up sheet showing the current vibe's structure, per-track instruments,
 * and now-playing state.
 *
 * Collects three flows:
 * - [pulsar.stateFlow] — provides the live [Vibe] and current energy level.
 * - [pulsar.arrangementStateFlow] — provides the current section index.
 * - [vizFlow] — provides per-track audio levels for isPlaying dots.
 *
 * Energy source: `PulsarUiState.energy` (live value updated as the user moves the
 * energy knob). Falls back to `vibe.energy` at rest; live value is preferred
 * because it reflects the engine's active track selection (≥0.5 = EDM engine).
 */
@Composable
fun VibeInfoSheet(
    pulsar: PulsarFeature,
    vizFlow: StateFlow<PulsarVizData>,
    onDismiss: () -> Unit,
) {
    val model = rememberVibeInfoModel(pulsar, vizFlow)

    OrpheusSlideUpSheet(
        onDismiss = onDismiss,
        skipPartiallyExpanded = true,
        inactivityTimeoutMs = null,
    ) {
        VibeInfoContent(model = model)
    }
}

/**
 * Builds the vibe readout model. Shared by the sheet and the docked panel so the hysteresis
 * and memoization below apply identically however the readout is presented.
 */
@Composable
private fun rememberVibeInfoModel(
    pulsar: PulsarFeature,
    vizFlow: StateFlow<PulsarVizData>,
): VibeInfoUiModel {
    val uiState by pulsar.stateFlow.collectAsState()
    val arrangement by pulsar.arrangementStateFlow.collectAsState()
    val viz by vizFlow.collectAsState()

    // The C++ engine re-rolls the active engine per audio block in the 0.4–0.6
    // crossfade zone, so viz.activeEngines flickers at mid-energy. Stabilize it
    // with hysteresis before mapping so the instrument label stays put.
    val stableViz = rememberStableViz(viz)

    // Memoize the mapping so it (and its per-track FmPatchNames lookups + fresh
    // list/model allocation) only runs when something mapVibeInfo actually reads
    // has changed. mapVibeInfo only reads two things off viz: activeEngines (per
    // track, already hysteresis-stabilized above) and trackLevels (collapsed here
    // into a per-track playing/not-playing bit). Keying on those two — instead of
    // stableViz itself — matters because PulsarVizData.equals() deep-compares
    // EVERY field, including stepGates/stepVelocities/playheads that this panel
    // never displays and that change on essentially every ~16ms poll while
    // playing; that made the old key "different" almost every frame. Both new
    // keys are cheap to compare: activeEngines keeps array-reference equality
    // (Kotlin's synthesized data-class equals does not deep-compare arrays), and
    // VizStabilizer only swaps in a new array when a commit actually happens;
    // playingMask is a single Int that only changes when a track crosses the
    // threshold.
    val playingMask = playingTrackMask(viz)
    return remember(uiState.vibe, arrangement, uiState.energy, stableViz.activeEngines, playingMask) {
        mapVibeInfo(
            vibe = uiState.vibe,
            arrangement = arrangement,
            viz = stableViz,
            energy = uiState.energy,
        )
    }
}

/**
 * Packs which tracks are audibly playing (trackLevels[i] > threshold) into a bitmask,
 * matching the exact comparison [mapVibeInfo] performs for [VibeInfoTrack.isPlaying].
 * Used only as a cheap remember() key — see [rememberVibeInfoModel].
 */
private fun playingTrackMask(
    viz: PulsarVizData,
    threshold: Float = DEFAULT_TRACK_LEVEL_THRESHOLD,
): Int {
    var mask = 0
    for (i in 0 until PULSAR_NUM_TRACKS) {
        if (viz.trackLevels.getOrElse(i) { 0f } > threshold) mask = mask or (1 shl i)
    }
    return mask
}

/**
 * The same content as [VibeInfoSheet], docked as a panel instead of slid up over the stage.
 * TV mode has room to keep the vibe readout on screen alongside everything else, where a
 * modal sheet would cover the layout it is describing.
 */
@Composable
fun VibeInfoPanel(
    pulsar: PulsarFeature,
    vizFlow: StateFlow<PulsarVizData>,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val model = rememberVibeInfoModel(pulsar, vizFlow)

    CollapsibleColumnPanel(
        title = "INFO",
        // No heading: docked panels in TV mode are headerless, and the content already
        // opens with the vibe's own name.
        expandedTitle = null,
        color = OrpheusColors.cosmicPurple,
        isExpanded = true,
        onExpandedChange = {},
        showCollapsedHeader = false,
        fillHeight = fillHeight,
        modifier = modifier,
    ) {
        VibeInfoContent(model = model)
    }
}

/**
 * Number of consecutive polls a new per-track active-engine id must persist
 * before it is committed for display. Derived from the ~400ms hold window and
 * SynthEngineMonitor's `VIZ_POLL_INTERVAL_MS` (16ms, ~60fps): ceil(400 / 16) = 25.
 * That constant is private to SynthEngineMonitor, so the value is inlined here.
 */
private const val HOLD_FRAMES = 25

/**
 * Per-track hysteresis state for [rememberStableViz]. Mutated in place across
 * recompositions — held in [remember], no [androidx.compose.runtime.mutableStateOf].
 */
private class VizStabilizer {
    val committed = IntArray(org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS) { -1 }
    val candidate = IntArray(org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS) { -1 }
    val counter = IntArray(org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS)

    // Immutable snapshot of [committed], re-copied only when the committed set
    // actually changes — so recompositions that don't move the committed engines
    // reuse the same array instead of allocating one per frame.
    var committedSnapshot: IntArray = committed.copyOf()

    // The raw viz object the hysteresis last advanced against. Guards the
    // per-poll counter so it advances once per new poll sample, not once per
    // recomposition (e.g. energy-knob drags reuse the same viz object).
    var lastProcessed: PulsarVizData? = null

    // Memoized stabilized view; re-derived only when a new viz sample arrives.
    var result: PulsarVizData? = null
}

/**
 * Debounces [PulsarVizData.activeEngines] so the instrument label doesn't flicker
 * while the DSP re-rolls the active engine per audio block in the crossfade zone.
 *
 * Per track: when the incoming id differs from the committed id and matches the
 * pending candidate, increment a counter; once it reaches [HOLD_FRAMES] the
 * candidate is committed. A different id resets the candidate + counter. Only the
 * committed ids feed the mapper. Returns [viz] with a stabilized `activeEngines`.
 */
@Composable
private fun rememberStableViz(viz: PulsarVizData): PulsarVizData {
    val state = remember { VizStabilizer() }

    // Advance the hold counters once per POLL, not once per recomposition. The
    // viz StateFlow conflates by structural equality, so each distinct object
    // reference collectAsState observes is a genuinely new sample; recompositions
    // driven by other state (e.g. the energy knob) reuse the same viz object.
    // Guarding on reference identity keeps the ~400ms (HOLD_FRAMES) hysteresis
    // tied to real polls, so it can't collapse and reintroduce label flicker.
    if (viz !== state.lastProcessed) {
        state.lastProcessed = viz
        val committed = state.committed
        var committedChanged = false
        for (t in committed.indices) {
            val incoming = viz.activeEngines.getOrElse(t) { -1 }
            when {
                incoming == committed[t] -> {
                    // Already showing this — clear any pending candidate.
                    state.candidate[t] = incoming
                    state.counter[t] = 0
                }
                incoming == state.candidate[t] -> {
                    if (++state.counter[t] >= HOLD_FRAMES) {
                        committed[t] = incoming
                        state.counter[t] = 0
                        committedChanged = true
                    }
                }
                else -> {
                    // New candidate — restart the hold count.
                    state.candidate[t] = incoming
                    state.counter[t] = 1
                }
            }
        }
        // Re-copy the committed set only when it actually moved.
        if (committedChanged) {
            state.committedSnapshot = committed.copyOf()
        }
        // Derive the stabilized view once per poll and cache it, so non-poll
        // recompositions return the same object (stable memoization key).
        state.result = viz.copy(activeEngines = state.committedSnapshot)
    }
    return state.result!!
}

/**
 * Pure layout composable for the vibe info rows. Factored out so both the live
 * [VibeInfoSheet] and the [PreviewVibeInfoContent] preview call the same renderer
 * without needing a real [PulsarFeature].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VibeInfoContent(
    model: VibeInfoUiModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        Text(
            text = model.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = OrpheusColors.onSurfaceDark,
            modifier = Modifier .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 6.dp),
        )

        // ── Meta line: BPM · Key · Scale ────────────────────────────────────
        Text(
            text = "${model.bpm} BPM  ·  ${model.keyName}  ·  ${model.scaleName}",
            fontSize = 12.sp,
            color = OrpheusColors.onSurfaceDark.copy(alpha = 0.55f),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp),
        )

        // ── Section strip ────────────────────────────────────────────────────
        if (model.sections.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                model.sections.forEach { section ->
                    SectionChip(section = section)
                }
            }
        }

        HorizontalDivider(
            color = OrpheusColors.cosmicPurple.copy(alpha = 0.20f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // ── Track rows ───────────────────────────────────────────────────────
        model.tracks.forEachIndexed { index, track ->
            TrackRow(track = track)
            if (index < model.tracks.lastIndex) {
                HorizontalDivider(
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.10f),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        HorizontalDivider(
            color = OrpheusColors.cosmicPurple.copy(alpha = 0.20f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // ── Reverb / delay summary pills ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EffectPill(label = "Reverb", pct = model.reverbPct)
            EffectPill(label = "Delay", pct = model.delayPct)
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun SectionChip(section: VibeInfoSection) {
    val bgColor = when {
        section.isNowPlaying -> OrpheusColors.neonCyan.copy(alpha = 0.18f)
        section.isPast       -> OrpheusColors.onSurfaceDark.copy(alpha = 0.05f)
        else                 -> OrpheusColors.cosmicPurple.copy(alpha = 0.10f)
    }
    val textColor = when {
        section.isNowPlaying -> OrpheusColors.neonCyan
        section.isPast       -> OrpheusColors.onSurfaceDark.copy(alpha = 0.30f)
        else                 -> OrpheusColors.onSurfaceDark.copy(alpha = 0.70f)
    }
    Text(
        text = section.name,
        fontSize = 11.sp,
        fontWeight = if (section.isNowPlaying) FontWeight.SemiBold else FontWeight.Normal,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrackRow(track: VibeInfoTrack, modifier: Modifier = Modifier) {
    val dotColor = if (track.isPlaying) OrpheusColors.neonCyan else OrpheusColors.onSurfaceDark.copy(alpha = 0.18f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Activity dot — glows neonCyan when audio level is above threshold
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
                .let { m ->
                    if (track.isPlaying) m.drawBehind {
                        drawCircle(
                            color = dotColor.copy(alpha = 0.35f),
                            radius = size.minDimension * 0.85f,
                        )
                    } else m
                },
        )
        Spacer(modifier = Modifier.width(10.dp))

        // Track label (KICK, PERC, TEXTURE, …). Single line, widened so the
        // longest label ("TEXTURE") never wraps.
        Text(
            text = track.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = OrpheusColors.onSurfaceDark.copy(alpha = 0.50f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(72.dp),
        )

        // Instrument name — primary info
        Text(
            text = track.instrument,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            color = OrpheusColors.onSurfaceDark,
            modifier = Modifier.weight(1f),
        )

        // Role badge (Drums/Perc, Lead/Melodic, Chords)
        Text(
            text = track.role,
            fontSize = 10.sp,
            color = OrpheusColors.onSurfaceDark.copy(alpha = 0.40f),
        )
    }
}

@Composable
private fun EffectPill(label: String, pct: Int, modifier: Modifier = Modifier) {
    val accentColor = if (label == "Reverb") OrpheusColors.echoLavender else OrpheusColors.echoPeriwinkle
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = accentColor,
        )
        Text(
            text = "$pct%",
            fontSize = 11.sp,
            color = OrpheusColors.onSurfaceDark.copy(alpha = 0.70f),
        )
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────

private fun fixtureVibeInfoModel(): VibeInfoUiModel = VibeInfoUiModel(
    name = "Dog House",
    bpm = 128,
    keyName = "D",
    scaleName = "Minor",
    sections = listOf(
        VibeInfoSection(name = "Intro", isNowPlaying = false, isPast = true),
        VibeInfoSection(name = "Verse", isNowPlaying = true, isPast = false),
        VibeInfoSection(name = "Chorus", isNowPlaying = false, isPast = false),
        VibeInfoSection(name = "Bridge", isNowPlaying = false, isPast = false),
        VibeInfoSection(name = "Outro", isNowPlaying = false, isPast = false),
    ),
    tracks = listOf(
        VibeInfoTrack(label = "KICK",    role = "Drums/Perc",   instrument = "Kick 808",      isPlaying = true),
        VibeInfoTrack(label = "PERC",    role = "Drums/Perc",   instrument = "Snare Crack",   isPlaying = true),
        VibeInfoTrack(label = "HIHAT",   role = "Drums/Perc",   instrument = "Open HH",       isPlaying = false),
        VibeInfoTrack(label = "BASS",    role = "Lead/Melodic", instrument = "Subtractive",   isPlaying = true),
        VibeInfoTrack(label = "KEYS",    role = "Lead/Melodic", instrument = "FM Synth",      isPlaying = false),
        VibeInfoTrack(label = "PAD",     role = "Chords",       instrument = "Wavetable",     isPlaying = true),
        VibeInfoTrack(label = "TEXTURE", role = "Lead/Melodic", instrument = "Grain Cloud",   isPlaying = false),
        VibeInfoTrack(label = "FX",      role = "Lead/Melodic", instrument = "Modal Voice",   isPlaying = false),
    ),
    reverbPct = 62,
    delayPct = 38,
)

@Preview(name = "VibeInfoContent – now playing", widthDp = 360, heightDp = 560)
@Composable
private fun PreviewVibeInfoContent() {
    OrpheusTheme {
        Surface(color = OrpheusColors.deepPurple, contentColor = OrpheusColors.onSurfaceDark) {
            VibeInfoContent(model = fixtureVibeInfoModel())
        }
    }
}

@Preview(name = "VibeInfoContent – no sections", widthDp = 360, heightDp = 400)
@Composable
private fun PreviewVibeInfoNoSections() {
    OrpheusTheme {
        Surface(color = OrpheusColors.deepPurple, contentColor = OrpheusColors.onSurfaceDark) {
            VibeInfoContent(
                model = fixtureVibeInfoModel().copy(sections = emptyList()),
            )
        }
    }
}
