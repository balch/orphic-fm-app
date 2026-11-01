package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.balch.orpheus.djapp.LegibleText
import org.balch.orpheus.djapp.legible
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot

private val ROW_HEIGHT = 40.dp
private val ROW_GAP = 6.dp
private val INLINE_SLIDER_GAP = 14.dp

/** At or above this width the pan, R and D sliders sit inline in each row; the expanded row then holds the fader. */
private val INLINE_SLIDERS_MIN = WIDE_MIN

/**
 * One column grid for every row, so names, meters, dB, LU and M/S line up down the list. The text columns widen
 * with the legible text, so a shrunk canvas never clips them.
 */
@Immutable
private class Grid(textScale: Float) {
    val name = 46.dp * textScale
    val pin = 22.dp * textScale
    val meter = 30.dp
    val db = 44.dp * textScale
    val lu = 40.dp * textScale
    val ms = 56.dp * textScale
    val sliderLabel = 24.dp * textScale
    val sliderValue = 34.dp * textScale
    val inlineSlider = 60.dp + sliderLabel + sliderValue
}

internal fun rowTag(index: Int) = "mixer-row-$index"

/**
 * The phone mixer: one single-line row per track (tap to expand its fader and sends, one at a time), then the
 * read-only sources, the bus and the changes. Meters reach only draw lambdas and the LU and bus text leafs.
 */
@Composable
internal fun CompactMixer(
    state: AiMixerUiState,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    flashes: AgentFlashes,
    width: Dp,
    modifier: Modifier = Modifier,
    bottomOverhang: Dp = 0.dp,
) {
    var expanded by rememberSaveable { mutableIntStateOf(-1) }
    val inline = width >= INLINE_SLIDERS_MIN
    val textScale = textScale()
    val grid = remember(textScale) { Grid(textScale) }
    LazyColumn(
        modifier.fillMaxSize(),
        // The bar's chrome overhangs the bottom edge, so the last rows scroll clear of it.
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp + bottomOverhang),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            Header(state.vibeName, state.aiCostUsd, meters, stacked = width < WIDE_MIN)
        }
        item(key = "macros") {
            KnobsAndChips(
                state,
                actions,
                beside = width >= KNOBS_BESIDE_CHIPS_MIN,
                knobSize = 34.dp,
                knobWidth = 76.dp * textScale,
                flashes = flashes,
            ) {
                ScopeToggle(state, actions.setScope, flashKey = flashes.count(FlashTarget.EditScope))
                if (state.sectionScope) SectionPanNote()
            }
        }
        items(state.tracks, key = { "track-${it.index}" }) { track ->
            val i = track.index
            TrackRow(
                track,
                meters,
                actions,
                grid,
                pinned = i in state.pins,
                agentTouched = i in state.agentTouched,
                flashKey = flashes.track(i),
                expanded = expanded == i,
                inline = inline,
                onToggle = { expanded = if (expanded == i) -1 else i },
            )
        }
        item(key = "sources-head") { Kicker("SOURCES  meter only") }
        items(SOURCES, key = { "source-${it.first}" }) { (ch, name, _) ->
            SourceRow(ch, name, meters, grid, inline)
        }
        item(key = "bus-head") { Kicker("BUS  peak dBFS") }
        items(listOf(DRY, OUT), key = { "bus-$it" }) { ch -> BusRow(ch, meters, grid) }
        item(key = "changes") {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MixerInk.card)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val nowMs = rememberNowMs()
                HistoryTitle(state, actions)
                HistoryTimeline(state, actions, nowMs, Modifier.fillMaxWidth())
                ChangesFooter(state, actions)
            }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(
        text,
        style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim, FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** One track on one line; tapping it opens the level fader (and, when narrow, the pan and sends) below. */
@Composable
private fun TrackRow(
    track: MixerTrack,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    grid: Grid,
    pinned: Boolean,
    agentTouched: Boolean,
    flashKey: Int,
    expanded: Boolean,
    inline: Boolean,
    onToggle: () -> Unit,
) {
    val i = track.index
    val color = MixerInk.tracks.getOrElse(i) { MixerInk.accent }
    val shape = RoundedCornerShape(8.dp)
    val border = when {
        expanded -> MixerInk.accent
        pinned -> MixerInk.pin
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(agentMark(flashKey, agentTouched, corner = 8.dp))
            .graphicsLayer { alpha = if (track.silenced) 0.5f else 1f }
            .clip(shape)
            .background(MixerInk.card)
            .border(1.dp, border, shape),
    ) {
        GridRow(Modifier.testTag(rowTag(i)).clickable(onClick = onToggle)) {
            RowName(track.name, color, grid)
            PinToggle(i, track.name, pinned, { actions.togglePin(i) }, size = grid.pin - 4.dp)
            MixerWave({ meters.waveOf(i) }, color, Modifier.weight(1f).height(22.dp))
            if (inline) Sends(track, actions, grid, Modifier.width(grid.inlineSlider))
            MixerMeter({ meters.fillOf(i) }, Modifier.width(grid.meter).height(6.dp), vertical = false)
            Text(
                dbText(track.levelDb),
                style = mixText(legible(LegibleText.MIXER_VALUE), if (abs(track.levelDb) > 0.05f) MixerInk.changed else MixerInk.text),
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(grid.db),
            )
            LuText(meters, i, Modifier.width(grid.lu))
            MuteSoloButtons(track.muted, track.soloed, { actions.toggleMute(i) }, { actions.toggleSolo(i) }, Modifier.width(grid.ms))
        }
        if (expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(
                    track.engine,
                    style = mixText(legible(LegibleText.MIXER_MIN), MixerInk.dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DbFaderRow(
                    db = track.levelDb,
                    onDbChange = { actions.setLevelDb(i, it) },
                    onReset = { actions.setLevelDb(i, 0f) },
                    index = i,
                    modifier = Modifier.fillMaxWidth(),
                    labelWidth = grid.sliderLabel + 12.dp,
                    valueWidth = grid.db + 8.dp,
                )
                if (!inline) {
                    Sends(track, actions, grid, Modifier.fillMaxWidth(), height = 30.dp, stacked = true)
                }
            }
        }
    }
}

/** Pan, R and D: side by side inline unless [stacked], else one under another at [height] under an expanded row. */
@Composable
private fun Sends(
    track: MixerTrack,
    actions: AiMixerActions,
    grid: Grid,
    modifier: Modifier,
    height: Dp = SLIDER_HEIGHT,
    stacked: Boolean = false,
) {
    val i = track.index
    // Stacked rows line their labels and values up with the level row above.
    val labelWidth = if (stacked) grid.sliderLabel + 12.dp else grid.sliderLabel
    val valueWidth = if (stacked) grid.db + 8.dp else grid.sliderValue
    @Composable
    fun sliders(m: Modifier) {
        MixerSlider(
            "pan", track.pan, -1f..1f, { actions.setPan(i, it) }, ::panText, i, m,
            onReset = { actions.setPan(i, track.filePan) },
            labelWidth = labelWidth, valueWidth = valueWidth, height = height,
        )
        MixerSlider("R", track.reverb, 0f..1f, { actions.setReverb(i, it) }, ::f2, i, m, labelWidth = labelWidth, valueWidth = valueWidth, height = height)
        MixerSlider("D", track.delay, 0f..1f, { actions.setDelay(i, it) }, ::f2, i, m, labelWidth = labelWidth, valueWidth = valueWidth, height = height)
    }
    if (stacked) {
        Column(modifier) { sliders(Modifier.fillMaxWidth()) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(INLINE_SLIDER_GAP)) { sliders(modifier) }
    }
}

/** A read-only source: wave, meter and loudness, columns aligned with the tracks'. */
@Composable
private fun SourceRow(ch: Int, name: String, meters: State<MeterSnapshot>, grid: Grid, inline: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    GridRow(Modifier.clip(shape).background(MixerInk.sourceCard).border(1.dp, MixerInk.line, shape)) {
        RowName(name, MixerInk.dim, grid)
        Spacer(Modifier.width(grid.pin))
        MixerWave({ meters.waveOf(ch) }, MixerInk.dim, Modifier.weight(1f).height(22.dp))
        if (inline) Spacer(Modifier.width(grid.inlineSlider * 3 + INLINE_SLIDER_GAP * 2))
        MixerMeter({ meters.fillOf(ch) }, Modifier.width(grid.meter).height(6.dp), vertical = false)
        Spacer(Modifier.width(grid.db))
        LuText(meters, ch, Modifier.width(grid.lu))
        Spacer(Modifier.width(grid.ms))
    }
}

/** DRY or OUT: a full-width meter and its peak. */
@Composable
private fun BusRow(ch: Int, meters: State<MeterSnapshot>, grid: Grid) {
    val shape = RoundedCornerShape(8.dp)
    GridRow(Modifier.clip(shape).background(MixerInk.sourceCard).border(1.dp, MixerInk.line, shape)) {
        RowName(BenchMeters.CHANNEL_NAMES[ch], MixerInk.text, grid)
        MixerMeter({ meters.fillOf(ch) }, Modifier.weight(1f).height(6.dp), vertical = false)
        PeakText(meters, ch, Modifier.width(grid.db))
        Spacer(Modifier.width(grid.lu + grid.ms + ROW_GAP))
    }
}

@Composable
private fun GridRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        content = content,
    )
}

@Composable
private fun RowName(name: String, color: Color, grid: Grid) {
    Text(
        name,
        style = mixText(legible(LegibleText.MIXER_MIN), color, FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(grid.name),
    )
}
