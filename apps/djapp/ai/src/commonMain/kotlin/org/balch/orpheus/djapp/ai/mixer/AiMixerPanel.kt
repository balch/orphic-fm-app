package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot

/** Full strips need this width and [WIDE_MIN_HEIGHT]; anything smaller gets the compact list. */
internal val WIDE_MIN = 600.dp

/** Below this content height the strips' faders fall under the fold, so short windows (phone landscape) go compact too. */
private val WIDE_MIN_HEIGHT = 400.dp

/** Under this height the strips tighten their padding, gaps, waves and knobs. */
private val TIGHT_HEIGHT = 480.dp

/** The height the header, knobs and strip furniture take, normal and tight; the faders get the rest. */
private val FURNITURE = 360.dp
private val TIGHT_FURNITURE = 316.dp

/** The fader's least height: 72dp of travel inside its 6dp padding. */
private val MIN_FADER = 84.dp

private val TIGHT_WAVE_HEIGHT = 20.dp

/** At or above this width the changes column sits beside the strips; below it, under them. */
private val SIDE_BESIDE_MIN = 1100.dp

private val SIDE_WIDTH = 260.dp

/** At or above this width the macro knobs share a row with the section chips. */
internal val KNOBS_BESIDE_CHIPS_MIN = 760.dp

private val SOURCE_STRIP_WIDTH = 64.dp
private val STRIP_GAP = 6.dp
private val BUS_STRIP_WIDTH = 76.dp
private val WAVE_HEIGHT = 28.dp
private val WAVE_PAD = 5.dp

internal const val DRY = 11
internal const val OUT = 12
internal val SOURCES = listOf(Triple(8, "STORM", "weather"), Triple(9, "STREET", "street"), Triple(10, "SPEECH", "speech"))

/**
 * The AI edition's mixer: the desktop Mix Bench rebuilt natively. Meters are collected here once, while shown, and
 * handed down as a [State] that only draw lambdas and the LU and bus text leafs read.
 *
 * [bottomOverhang] is how far the bar's chrome reaches up over the panel's bottom edge: the strips' surface stops
 * above it, and the compact list scrolls its last rows clear of it.
 */
@Composable
fun AiMixerPanel(
    feature: AiMixerFeature,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    bottomOverhang: Dp = 0.dp,
) {
    val state by feature.stateFlow.collectAsState()
    val meters = feature.meters.collectAsState(EMPTY_METERS)
    BoxWithConstraints(modifier) {
        val wideHeight = maxHeight - bottomOverhang
        val wide = maxWidth >= WIDE_MIN && wideHeight >= WIDE_MIN_HEIGHT
        Surface(
            Modifier.fillMaxSize().padding(bottom = if (wide) bottomOverhang else 0.dp),
            shape = shape,
            color = MixerInk.surface,
            contentColor = MixerInk.text,
        ) {
            if (wide) {
                WideMixer(state, meters, feature.actions, maxWidth, wideHeight)
            } else {
                CompactMixer(state, meters, feature.actions, maxWidth, bottomOverhang = bottomOverhang)
            }
        }
    }
}

@Composable
private fun WideMixer(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    width: Dp,
    height: Dp,
) {
    // Short windows (a 1280x800 desktop under the dome's clearance) tighten the furniture so the faders keep travel.
    val tight = height < TIGHT_HEIGHT
    val pad = if (tight) 8.dp else 12.dp
    val gap = if (tight) 6.dp else 10.dp
    val wave = if (tight) TIGHT_WAVE_HEIGHT else WAVE_HEIGHT
    val sideBeside = width >= SIDE_BESIDE_MIN
    val stripsWidth = width - pad * 2 - if (sideBeside) SIDE_WIDTH + pad else 0.dp
    // The eight tracks share what the sources and the bus leave, within readable limits.
    val stripWidth = ((stripsWidth - BUS_STRIP_WIDTH - SOURCE_STRIP_WIDTH * 3 - STRIP_GAP * 11 - 2.dp) / 8)
        .coerceIn(72.dp, 132.dp)
    // What is left of the height after the header rows and the strip furniture goes to the faders.
    val faderHeight = (height - if (tight) TIGHT_FURNITURE else FURNITURE).coerceIn(MIN_FADER, 360.dp)
    val knobSize = when {
        tight -> 32.dp
        height < 500.dp -> 38.dp
        else -> 46.dp
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Header(state.vibeName, meters, stacked = false)
        KnobsAndChips(state, actions, beside = width >= KNOBS_BESIDE_CHIPS_MIN, knobSize = knobSize)
        if (sideBeside) {
            StripsBeside(
                strips = { Strips(state, meters, actions, stripWidth, faderHeight, wave) },
                side = { SideColumn(state, actions, Modifier.fillMaxSize(), fitHeight = true) },
                gap = pad,
            )
        } else {
            Strips(state, meters, actions, stripWidth, faderHeight, wave, Modifier.fillMaxWidth())
            SideColumn(state, actions, Modifier.fillMaxWidth())
        }
    }
}

/** The strips, then the changes column at [SIDE_WIDTH] and exactly the strips' height, so it never makes the page taller. */
@Composable
private fun StripsBeside(strips: @Composable () -> Unit, side: @Composable () -> Unit, gap: Dp) {
    Layout(content = { strips(); side() }) { measurables, constraints ->
        val sideWidth = SIDE_WIDTH.roundToPx()
        val stripsWidth = (constraints.maxWidth - sideWidth - gap.roundToPx()).coerceAtLeast(0)
        val s = measurables[0].measure(Constraints(maxWidth = stripsWidth, maxHeight = constraints.maxHeight))
        val c = measurables[1].measure(Constraints.fixed(sideWidth, s.height))
        layout(constraints.maxWidth, s.height) {
            s.place(0, 0)
            c.place(constraints.maxWidth - sideWidth, 0)
        }
    }
}

/**
 * The macro knobs and the section chips: one row when [beside], else knobs (scrolling if cramped) over chips.
 * [underChips] goes right under the chips either way.
 */
@Composable
internal fun KnobsAndChips(
    state: AiMixerUiState,
    actions: AiMixerActions,
    beside: Boolean,
    knobSize: Dp,
    knobWidth: Dp = 88.dp,
    underChips: (@Composable () -> Unit)? = null,
) {
    if (beside) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MixerMacroKnobs(state.macros, actions.setMacro, knobSize = knobSize, knobWidth = knobWidth)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionChips(state.sections, state.section, state.loopSection, actions.jumpTo, actions.setLoop, Modifier.fillMaxWidth())
                underChips?.invoke()
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MixerMacroKnobs(
                state.macros,
                actions.setMacro,
                Modifier.horizontalScroll(rememberScrollState()),
                knobSize = knobSize,
                knobWidth = knobWidth,
            )
            SectionChips(state.sections, state.section, state.loopSection, actions.jumpTo, actions.setLoop, Modifier.fillMaxWidth())
            underChips?.invoke()
        }
    }
}

@Composable
internal fun Header(vibeName: String, meters: State<MeterSnapshot>, stacked: Boolean) {
    // Narrow windows put the bus readout on its own line so the vibe name keeps its room.
    // No close button: Back/Esc, the scrim and the tab bar all close the sheet.
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Title(vibeName, Modifier.fillMaxWidth())
            BusReadout(meters, textAlign = TextAlign.Start, size = 12.5.sp)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(vibeName, Modifier.weight(1f))
        BusReadout(meters)
    }
}

@Composable
internal fun Title(vibeName: String, modifier: Modifier) {
    Column(modifier) {
        Text("MIXER", style = mixText(10.sp, MixerInk.dim, FontWeight.SemiBold), maxLines = 1)
        Text(
            vibeName.ifEmpty { "No vibe" },
            style = mixText(17.sp, MixerInk.text, FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun Strips(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    stripWidth: Dp,
    faderHeight: Dp,
    wave: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
    ) {
        for (track in state.tracks) {
            TrackStrip(track, meters, actions, stripWidth, faderHeight, wave)
        }
        for ((ch, name, what) in SOURCES) {
            SourceStrip(ch, name, what, meters, SOURCE_STRIP_WIDTH, faderHeight, wave)
        }
        BusStrip(meters, faderHeight, wave)
    }
}

/** The height of the pan, R and D rows, so the source and bus meters line up with the track faders. */
private val SLIDERS_HEIGHT = SLIDER_HEIGHT * 3

@Composable
private fun TrackStrip(
    track: MixerTrack,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    width: Dp,
    faderHeight: Dp,
    wave: Dp,
) {
    val i = track.index
    val color = MixerInk.tracks.getOrElse(i) { MixerInk.accent }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer { alpha = if (track.silenced) 0.5f else 1f }
            .clip(RoundedCornerShape(8.dp))
            .background(MixerInk.card)
            .padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StripTitle(track.name, track.engine, color)
        MixerWave({ meters.waveOf(i) }, color, Modifier.padding(vertical = WAVE_PAD).fillMaxWidth().height(wave))
        MixerSlider(
            "pan", track.pan, -1f..1f, { actions.setPan(i, it) }, ::panText, i,
            onReset = { actions.setPan(i, track.filePan) },
        )
        MixerSlider("R", track.reverb, 0f..1f, { actions.setReverb(i, it) }, ::f2, i)
        MixerSlider("D", track.delay, 0f..1f, { actions.setDelay(i, it) }, ::f2, i)
        DbFader(
            db = track.levelDb,
            onDbChange = { actions.setLevelDb(i, it) },
            onReset = { actions.setLevelDb(i, 0f) },
            index = i,
            height = faderHeight,
            meter = { MixerMeter({ meters.fillOf(i) }, Modifier.width(8.dp).fillMaxHeight()) },
        )
        LuText(meters, i)
        Spacer(Modifier.height(4.dp))
        MuteSoloButtons(track.muted, track.soloed, { actions.toggleMute(i) }, { actions.toggleSolo(i) })
    }
}

@Composable
private fun StripTitle(name: String, sub: String, color: Color) {
    Text(name, style = mixText(11.sp, color, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(sub, style = mixText(9.sp, MixerInk.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A read-only source: its wave, meter and loudness, nothing to move. */
@Composable
private fun SourceStrip(ch: Int, name: String, what: String, meters: State<MeterSnapshot>, width: Dp, faderHeight: Dp, wave: Dp) {
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(MixerInk.sourceCard)
            .border(1.dp, MixerInk.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StripTitle(name, what, MixerInk.dim)
        MixerWave({ meters.waveOf(ch) }, MixerInk.dim, Modifier.padding(vertical = WAVE_PAD).fillMaxWidth().height(wave))
        Spacer(Modifier.height(SLIDERS_HEIGHT))
        Box(Modifier.height(faderHeight).padding(vertical = 6.dp)) {
            MixerMeter({ meters.fillOf(ch) }, Modifier.width(8.dp).fillMaxHeight())
        }
        Text("meter only", style = mixText(9.sp, MixerInk.dim), maxLines = 1)
        LuText(meters, ch)
    }
}

/** The dry mix and the output, side by side, with their peaks. */
@Composable
private fun BusStrip(meters: State<MeterSnapshot>, faderHeight: Dp, wave: Dp) {
    Column(
        Modifier
            .width(BUS_STRIP_WIDTH)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(MixerInk.sourceCard)
            .border(1.dp, MixerInk.line, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StripTitle("BUS", "peak dB", MixerInk.text)
        Spacer(Modifier.height(wave + WAVE_PAD * 2 + SLIDERS_HEIGHT))
        Row(Modifier.height(faderHeight).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MixerMeter({ meters.fillOf(DRY) }, Modifier.width(8.dp).fillMaxHeight())
            MixerMeter({ meters.fillOf(OUT) }, Modifier.width(8.dp).fillMaxHeight())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(BenchMeters.CHANNEL_NAMES[DRY], style = mixText(10.sp), maxLines = 1)
                PeakText(meters, DRY)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(BenchMeters.CHANNEL_NAMES[OUT], style = mixText(10.sp), maxLines = 1)
                PeakText(meters, OUT)
            }
        }
    }
}

/** With [fitHeight] the list and footer scroll inside the height it is given; otherwise the list caps at 360dp. */
@Composable
private fun SideColumn(state: AiMixerUiState, actions: AiMixerActions, modifier: Modifier = Modifier, fitHeight: Boolean = false) {
    val lines = remember(state.changes) { changeLines(state.changes) }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MixerInk.card)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChangesTitle()
        ScopeToggle(state, actions.setScope)
        if (state.sectionScope) SectionPanNote()
        if (fitHeight) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChangesList(lines, Modifier.fillMaxWidth())
                ChangesFooter(state, actions)
            }
        } else {
            Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                ChangesList(lines, Modifier.fillMaxWidth())
            }
            ChangesFooter(state, actions)
        }
    }
}

@Composable
internal fun ChangesTitle() {
    Text("Changes", style = mixText(13.sp, MixerInk.text, FontWeight.SemiBold))
}

@Composable
internal fun SectionPanNote() {
    Text("Pan has no per-section value: pan moves still land in the whole vibe.", style = mixText(11.sp, MixerInk.dim))
}

/** Capture and Reset, the capture result, the notice and the fader note, under the changes list. */
@Composable
internal fun ChangesFooter(state: AiMixerUiState, actions: AiMixerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MixerButton(if (state.capturing) "Capturing…" else "Capture", actions.capture, primary = true, enabled = !state.capturing)
            MixerButton("Reset to file", actions.reset)
        }
        state.capture?.let { CaptureResult(it) }
        state.notice?.let { NoticeLine(it, actions.dismissNotice, Modifier.fillMaxWidth()) }
        Text(
            "Faders show the dB offset from the file (0 dB = as written). Pan and sends show the value heard in the playing section.",
            style = mixText(10.sp, MixerInk.dim),
        )
    }
}
