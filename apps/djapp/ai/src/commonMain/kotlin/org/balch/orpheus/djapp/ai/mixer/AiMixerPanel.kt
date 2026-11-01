package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.ai.chat.rememberOverlayFocus
import org.balch.orpheus.djapp.ai.chat.usd
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.EngineKnob
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot

/** Full strips need this width and [WIDE_MIN_HEIGHT]; anything smaller gets the compact list. */
internal val WIDE_MIN = 600.dp

/** Below this content height the strips' faders fall under the fold, so short windows (phone landscape) go compact too. */
private val WIDE_MIN_HEIGHT = 400.dp

/** Under this height the strips tighten their padding, gaps, waves and knobs. */
private val TIGHT_HEIGHT = 480.dp

private val TIGHT_WAVE_HEIGHT = 20.dp

/** At or above this width the macro knobs share a row with the section chips (compact list). */
internal val KNOBS_BESIDE_CHIPS_MIN = 760.dp

/** The least room the section chips need beside the knobs in the wide layout. */
private val CHIPS_MIN = 380.dp

/** At or above this width the bus readout shares the title's row. */
private val HEADER_ONE_ROW_MIN = 1200.dp

internal val AMBIENT_WIDTH = 64.dp
private val STRIP_GAP = 6.dp
private val BUS_STRIP_WIDTH = 76.dp
private val WAVE_HEIGHT = 28.dp
private val WAVE_PAD = 5.dp
private val TIGHT_WAVE_PAD = 3.dp

/** The gap between the macro knobs and the section chips beside them. */
private val KNOBS_CHIPS_GAP = 16.dp

internal const val DRY = 11
internal const val OUT = 12
internal val SOURCES = listOf(Triple(8, "STORM", "weather"), Triple(9, "STREET", "street"), Triple(10, "SPEECH", "speech"))

/** The AMBIENT strip's three meters: weather, street and speech. */
private val AMBIENT = listOf(8 to "S", 9 to "St", 10 to "Sp")

internal fun stripTag(index: Int) = "mixer-strip-$index"

internal const val AMBIENT_TAG = "mixer-ambient"
internal const val BUS_TAG = "mixer-bus"
internal const val HISTORY_TAG = "mixer-history"
internal const val HISTORY_SCRIM_TAG = "mixer-history-scrim"

/**
 * The AI edition's mixer: the desktop Mix Bench rebuilt natively. Meters are collected here once, while shown, and
 * handed down as a [State] that only draw lambdas and the LU and bus text leafs read. The agent's and MCP's edits
 * flash the control they changed.
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
    val flashes = remember { AgentFlashes() }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(feature) {
        feature.agentEdits.collect { control ->
            flashes.flash(control, latest.tracks.map { it.index }.filter { it !in latest.pins })
        }
    }
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
                WideMixer(state, meters, feature.actions, flashes, maxWidth, wideHeight)
            } else {
                CompactMixer(state, meters, feature.actions, flashes, maxWidth, bottomOverhang = bottomOverhang)
            }
        }
    }
}

@Composable
private fun WideMixer(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    flashes: AgentFlashes,
    width: Dp,
    height: Dp,
) {
    // Short windows (a 1280x800 desktop under the dome's clearance) tighten the furniture so the faders keep travel.
    val tight = height < TIGHT_HEIGHT
    val pad = if (tight) 8.dp else 12.dp
    val gap = if (tight) 4.dp else 10.dp
    val wave = if (tight) TIGHT_WAVE_HEIGHT else WAVE_HEIGHT
    val stripsWidth = width - pad * 2
    // The eight tracks share what the ambient and bus strips leave, within readable limits.
    val stripWidth = ((stripsWidth - BUS_STRIP_WIDTH - AMBIENT_WIDTH - STRIP_GAP * 9) / 8).coerceIn(72.dp, 132.dp)
    val knobSize = when {
        tight -> 32.dp
        height < 500.dp -> 38.dp
        else -> 46.dp
    }
    // Legible labels widen the knobs, so whether they fit beside the chips depends on the canvas scale too.
    val textScale = textScale()
    val knobWidth = max(88.dp, 76.dp * textScale)
    val beside = stripsWidth >= knobWidth * 4 + MACRO_KNOB_GAP * 3 + KNOBS_CHIPS_GAP + CHIPS_MIN
    var changesOpen by rememberSaveable { mutableStateOf(false) }
    var headerRowHeight by remember { mutableIntStateOf(0) }
    val overlay = rememberOverlayFocus()
    val closeChanges = {
        changesOpen = false
        overlay.onClose()
    }
    val scopeToggle: @Composable () -> Unit = {
        ScopeToggle(state, actions.setScope, flashKey = flashes.count(FlashTarget.EditScope))
    }
    // Esc closes the popover; every other key goes on as before.
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { overlay.onPreviewKey(it, changesOpen) { changesOpen = false } }
            .focusRequester(overlay.host)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad)) {
            TopThenStrips(
                viewport = height - pad * 2,
                gap = gap,
                top = {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        WideHeader(
                            state,
                            meters,
                            actions,
                            oneRow = width >= HEADER_ONE_ROW_MIN,
                            tight = tight,
                            onChanges = {
                                if (changesOpen) {
                                    closeChanges()
                                } else {
                                    overlay.onOpen()
                                    changesOpen = true
                                }
                            },
                            onRowHeight = { headerRowHeight = it },
                        )
                        KnobsAndChips(
                            state,
                            actions,
                            beside = beside,
                            knobSize = knobSize,
                            knobWidth = knobWidth,
                            flashes = flashes,
                            chipsTrailing = if (beside) null else scopeToggle,
                            underChips = if (beside) scopeToggle else null,
                        )
                        if (state.sectionScope) SectionPanNote()
                        VoiceNote()
                    }
                },
                strips = { Strips(state, meters, actions, flashes, stripWidth, wave, tight) },
            )
        }
        if (changesOpen) {
            val top = pad + with(LocalDensity.current) { headerRowHeight.toDp() } + 6.dp
            // Taps outside the popover close it.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(HISTORY_SCRIM_TAG)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close history",
                        onClick = closeChanges,
                    ),
            )
            HistoryPopover(
                state,
                actions,
                onClose = closeChanges,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = top, end = pad)
                    .width(min(stripsWidth, 560.dp * textScale))
                    .height((height - top - pad).coerceAtLeast(160.dp)),
            )
        }
    }
}

/** [top] at its own height, then [strips] stretched over the rest of [viewport], never under their least height (the column scrolls instead). */
@Composable
private fun TopThenStrips(viewport: Dp, gap: Dp, top: @Composable () -> Unit, strips: @Composable () -> Unit) {
    Layout(content = { top(); strips() }) { measurables, constraints ->
        val t = measurables[0].measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val g = gap.roundToPx()
        val least = measurables[1].minIntrinsicHeight(constraints.maxWidth)
        val h = (viewport.roundToPx() - t.height - g).coerceAtLeast(least)
        val s = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth, minHeight = h, maxHeight = h))
        layout(constraints.maxWidth, t.height + g + h) {
            t.place(0, 0)
            s.place(0, t.height + g)
        }
    }
}

/** Title, the changes button, Capture and Reset; the bus readout beside them when [oneRow], else under. */
@Composable
private fun WideHeader(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    oneRow: Boolean,
    tight: Boolean,
    onChanges: () -> Unit,
    onRowHeight: (Int) -> Unit,
) {
    // No close button: Back/Esc, the scrim and the tab bar all close the sheet.
    Column(verticalArrangement = Arrangement.spacedBy(if (tight) 3.dp else 6.dp)) {
        Row(
            Modifier.onSizeChanged { onRowHeight(it.height) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Title(state.vibeName, state.aiCostUsd, Modifier.weight(1f))
            if (oneRow) BusReadout(meters)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MixerButton(changesLabel(state.historyChangeCount), onChanges, Modifier.testTag(HISTORY_TAG))
                MixerButton(if (state.capturing) "Capturing…" else "Capture", actions.capture, primary = true, enabled = !state.capturing)
                MixerButton("Clear All", actions.reset)
            }
        }
        if (!oneRow) BusReadout(meters, textAlign = TextAlign.Start)
        state.capture?.let { CaptureResult(it, actions.dismissCapture, Modifier.fillMaxWidth()) }
        state.notice?.let { NoticeLine(it, actions.dismissNotice, Modifier.fillMaxWidth(), compact = tight) }
    }
}

/**
 * The macro knobs and the section chips: one row when [beside], else knobs (scrolling if cramped) over chips.
 * [chipsTrailing] ends the chips' row; [underChips] goes right under it either way.
 */
@Composable
internal fun KnobsAndChips(
    state: AiMixerUiState,
    actions: AiMixerActions,
    beside: Boolean,
    knobSize: Dp,
    knobWidth: Dp = 88.dp,
    flashes: AgentFlashes? = null,
    chipsTrailing: (@Composable () -> Unit)? = null,
    underChips: (@Composable () -> Unit)? = null,
) {
    @Composable
    fun chips() = SectionChips(
        state.sections,
        state.section,
        state.loopSection,
        actions.jumpTo,
        actions.setLoop,
        Modifier.fillMaxWidth(),
        flashes = flashes,
        trailing = chipsTrailing,
    )
    if (beside) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KNOBS_CHIPS_GAP)) {
            MixerMacroKnobs(state.macros, actions.setMacro, knobSize = knobSize, knobWidth = knobWidth, flashes = flashes)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chips()
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
                flashes = flashes,
            )
            chips()
            underChips?.invoke()
        }
    }
}

@Composable
internal fun Header(vibeName: String, aiCostUsd: Double?, meters: State<MeterSnapshot>, stacked: Boolean) {
    // Narrow windows put the bus readout on its own line so the vibe name keeps its room.
    // No close button: Back/Esc, the scrim and the tab bar all close the sheet.
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Title(vibeName, aiCostUsd, Modifier.fillMaxWidth())
            BusReadout(meters, textAlign = TextAlign.Start, nativeSize = 12f)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(vibeName, aiCostUsd, Modifier.weight(1f))
        BusReadout(meters)
    }
}

@Composable
internal fun Title(vibeName: String, aiCostUsd: Double?, modifier: Modifier) {
    Column(modifier) {
        // The AI's spend on this vibe rides the small label, so the vibe name keeps its width.
        Text("MIXER" + (aiCostUsd?.let { " · AI ${usd(it)}" } ?: ""), style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.SemiBold), maxLines = 1)
        Text(
            vibeName.ifEmpty { "No vibe" },
            style = mixText(legible(LegibleText.TITLE), MixerInk.text, FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 320.dp * textScale()),
        )
    }
}

@Composable
private fun Strips(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    flashes: AgentFlashes,
    stripWidth: Dp,
    wave: Dp,
    tight: Boolean,
) {
    Row(
        Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
    ) {
        for (track in state.tracks) {
            val i = track.index
            TrackStrip(track, meters, actions, i in state.pins, i in state.agentTouched, flashes.track(i), stripWidth, wave, tight)
        }
        AmbientStrip(meters)
        BusStrip(meters)
    }
}

/**
 * One track: name, pin and engine, wave, pan and send knobs, the fader (which takes the height left), loudness and
 * mute/solo. A pinned strip is outlined; the agent's marks lie over it.
 */
@Composable
private fun TrackStrip(
    track: MixerTrack,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    pinned: Boolean,
    agentTouched: Boolean,
    flashKey: Int,
    width: Dp,
    wave: Dp,
    tight: Boolean,
) {
    val i = track.index
    val color = MixerInk.tracks.getOrElse(i) { MixerInk.accent }
    val shape = RoundedCornerShape(8.dp)
    var readout by remember { mutableStateOf<String?>(null) }
    val knob = ((width - 16.dp) / 2).coerceIn(24.dp, 34.dp)
    val knobsOneRow = width - 8.dp >= knob * 3 + 12.dp
    val panKnob: @Composable () -> Unit = {
        StripKnob(
            "pan", track.pan, -1f..1f, { actions.setPan(i, it) }, { v -> readout = v?.let(::panText) }, knob,
            onReset = { actions.setPan(i, track.filePan) },
        )
    }
    Column(
        Modifier
            .testTag(stripTag(i))
            .width(width)
            .fillMaxHeight()
            .then(agentMark(flashKey, agentTouched, corner = 8.dp))
            .graphicsLayer { alpha = if (track.silenced) 0.5f else 1f }
            .clip(shape)
            .background(MixerInk.card)
            .then(if (pinned) Modifier.border(1.5.dp, MixerInk.pin, shape) else Modifier)
            .padding(horizontal = 3.dp, vertical = if (tight) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name,
                style = mixText(legible(LegibleText.MIXER_MIN), color, FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // A small pin leaves the name room on a narrow strip; its touch target stays PIN_TARGET.
            PinToggle(i, track.name, pinned, { actions.togglePin(i) }, size = 12.dp, anchor = Alignment.TopEnd)
        }
        SlotHeader(track) { actions.pickSlot(i, it) }
        VoiceKnobRow(
            track,
            onSet = { t, k, v -> actions.setVoiceKnob(t, EngineKnob.entries[k], v) },
            onHeld = { readout = it },
            size = ((width - 6.dp) / 3 - 6.dp).coerceIn(18.dp, 30.dp),
        )
        Box(Modifier.padding(vertical = if (tight) TIGHT_WAVE_PAD else WAVE_PAD).fillMaxWidth().height(wave), contentAlignment = Alignment.Center) {
            MixerWave({ meters.waveOf(i) }, color, Modifier.fillMaxSize())
            readout?.let { KnobReadout(it, Modifier.wrapContentSize(unbounded = true)) }
        }
        // A wide strip fits all three knobs on one row, leaving the fader more height.
        if (!knobsOneRow) panKnob()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (knobsOneRow) panKnob()
            StripKnob("R", track.reverb, 0f..1f, { actions.setReverb(i, it) }, { v -> readout = v?.let(::f2) }, knob)
            StripKnob("D", track.delay, 0f..1f, { actions.setDelay(i, it) }, { v -> readout = v?.let(::f2) }, knob)
        }
        DbFader(
            db = track.levelDb,
            onDbChange = { actions.setLevelDb(i, it) },
            onReset = { actions.setLevelDb(i, 0f) },
            index = i,
            modifier = Modifier.weight(1f),
            height = null,
            meter = { MixerMeter({ meters.fillOf(i) }, Modifier.width(8.dp).fillMaxHeight()) },
        )
        LuText(meters, i)
        Spacer(Modifier.height(4.dp))
        MuteSoloButtons(track.muted, track.soloed, { actions.toggleMute(i) }, { actions.toggleSolo(i) })
    }
}

/** Storm, street and speech, read-only: one thin meter each. */
@Composable
private fun AmbientStrip(meters: State<MeterSnapshot>) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .testTag(AMBIENT_TAG)
            .width(AMBIENT_WIDTH)
            .fillMaxHeight()
            .clip(shape)
            .background(MixerInk.sourceCard)
            .border(1.dp, MixerInk.line, shape)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.Bold)
        val measurer = rememberTextMeasurer()
        val room = with(LocalDensity.current) { (AMBIENT_WIDTH - 6.dp).roundToPx() }
        // The word outgrows the strip on a shrunk canvas, so it shortens rather than ellipsizes.
        val title = remember(style, room) {
            if (measurer.measure("AMBIENT", style, maxLines = 1).size.width <= room) "AMBIENT" else "AMB"
        }
        Text(title, style = style, maxLines = 1)
        Row(
            Modifier.weight(1f).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        ) {
            for ((ch, label) in AMBIENT) {
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    MixerMeter({ meters.fillOf(ch) }, Modifier.weight(1f).padding(vertical = 6.dp).width(6.dp))
                    Text(label, style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1)
                }
            }
        }
    }
}

/** The dry mix and the output, side by side, with their peaks under them. */
@Composable
private fun BusStrip(meters: State<MeterSnapshot>) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .testTag(BUS_TAG)
            .width(BUS_STRIP_WIDTH)
            .fillMaxHeight()
            .clip(shape)
            .background(MixerInk.sourceCard)
            .border(1.dp, MixerInk.line, shape)
            .padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("BUS", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.text, FontWeight.Bold), maxLines = 1)
        Text("peak", style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim), maxLines = 1)
        Row(Modifier.weight(1f).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MixerMeter({ meters.fillOf(DRY) }, Modifier.width(8.dp).fillMaxHeight())
            MixerMeter({ meters.fillOf(OUT) }, Modifier.width(8.dp).fillMaxHeight())
        }
        for (ch in listOf(DRY, OUT)) {
            Text(BenchMeters.CHANNEL_NAMES[ch], style = mixText(legible(LegibleText.MIXER_MIN)), maxLines = 1)
            PeakText(meters, ch)
        }
    }
}

@Composable
internal fun SectionPanNote() {
    Text(
        "Pan has no per-section value: pan moves still land in the whole vibe.",
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
    )
}

@Composable
internal fun VoiceNote() {
    Text(
        "Track sound knobs: dim ones follow MOOD or SPACE, and turning one pins it from what you hear. Double-tap " +
            "returns it to the file. The switch picks the EDM or SPACE engine; • marks the one playing.",
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
    )
}

@Composable
internal fun FaderNote() {
    Text(
        "Faders show the dB offset from the file (0 = as written). Pan and sends show the value heard in the playing section.",
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
    )
}

/** Capture and Reset, the capture result, the notice and the fader note, under the compact list's changes. */
@Composable
internal fun ChangesFooter(state: AiMixerUiState, actions: AiMixerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MixerButton(if (state.capturing) "Capturing…" else "Capture", actions.capture, primary = true, enabled = !state.capturing)
            MixerButton("Clear All", actions.reset)
        }
        state.capture?.let { CaptureResult(it, actions.dismissCapture, Modifier.fillMaxWidth()) }
        state.notice?.let { NoticeLine(it, actions.dismissNotice, Modifier.fillMaxWidth()) }
        FaderNote()
    }
}
