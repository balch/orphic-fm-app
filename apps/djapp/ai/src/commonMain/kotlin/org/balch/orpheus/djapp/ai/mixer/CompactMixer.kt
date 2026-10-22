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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.MeterSnapshot

// One column grid for every row, so names, meters, dB, LU and M/S line up down the list.
private val ROW_HEIGHT = 40.dp
private val ROW_GAP = 6.dp
private val NAME_WIDTH = 46.dp
private val METER_WIDTH = 30.dp
private val DB_WIDTH = 44.dp
private val LU_WIDTH = 40.dp
private val MS_WIDTH = 52.dp
private val INLINE_SLIDER_WIDTH = 112.dp
private val INLINE_SLIDER_GAP = 14.dp

/** At or above this width the pan, R and D sliders sit inline in each row; the expanded row then holds the fader. */
private val INLINE_SLIDERS_MIN = WIDE_MIN

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
    width: Dp,
    modifier: Modifier = Modifier,
    bottomOverhang: Dp = 0.dp,
) {
    var expanded by rememberSaveable { mutableIntStateOf(-1) }
    val lines = remember(state.changes) { changeLines(state.changes) }
    val inline = width >= INLINE_SLIDERS_MIN
    LazyColumn(
        modifier.fillMaxSize(),
        // The bar's chrome overhangs the bottom edge, so the last rows scroll clear of it.
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp + bottomOverhang),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            Header(state.vibeName, meters, stacked = width < WIDE_MIN)
        }
        item(key = "macros") {
            KnobsAndChips(
                state,
                actions,
                beside = width >= KNOBS_BESIDE_CHIPS_MIN,
                knobSize = 34.dp,
                knobWidth = 76.dp,
            ) {
                ScopeToggle(state, actions.setScope)
                if (state.sectionScope) SectionPanNote()
            }
        }
        items(state.tracks, key = { "track-${it.index}" }) { track ->
            TrackRow(
                track,
                meters,
                actions,
                expanded = expanded == track.index,
                inline = inline,
                onToggle = { expanded = if (expanded == track.index) -1 else track.index },
            )
        }
        item(key = "sources-head") { Kicker("SOURCES  meter only") }
        items(SOURCES, key = { "source-${it.first}" }) { (ch, name, _) ->
            SourceRow(ch, name, meters, inline)
        }
        item(key = "bus-head") { Kicker("BUS  peak dBFS") }
        items(listOf(DRY, OUT), key = { "bus-$it" }) { ch -> BusRow(ch, meters) }
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
                ChangesTitle()
                ChangesList(lines, Modifier.fillMaxWidth())
                ChangesFooter(state, actions)
            }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(text, style = mixText(10.sp, MixerInk.dim, FontWeight.SemiBold), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
}

/** One track on one line; tapping it opens the level fader (and, when narrow, the pan and sends) below. */
@Composable
private fun TrackRow(
    track: MixerTrack,
    meters: State<MeterSnapshot>,
    actions: AiMixerActions,
    expanded: Boolean,
    inline: Boolean,
    onToggle: () -> Unit,
) {
    val i = track.index
    val color = MixerInk.tracks.getOrElse(i) { MixerInk.accent }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (track.silenced) 0.5f else 1f }
            .clip(shape)
            .background(MixerInk.card)
            .border(1.dp, if (expanded) MixerInk.accent else Color.Transparent, shape),
    ) {
        GridRow(Modifier.testTag(rowTag(i)).clickable(onClick = onToggle)) {
            RowName(track.name, color)
            MixerWave({ meters.waveOf(i) }, color, Modifier.weight(1f).height(22.dp))
            if (inline) Sends(track, actions, Modifier.width(INLINE_SLIDER_WIDTH))
            MixerMeter({ meters.fillOf(i) }, Modifier.width(METER_WIDTH).height(6.dp), vertical = false)
            Text(
                dbText(track.levelDb),
                style = mixText(10.sp, if (abs(track.levelDb) > 0.05f) MixerInk.changed else MixerInk.text),
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(DB_WIDTH),
            )
            LuText(meters, i, Modifier.width(LU_WIDTH))
            MuteSoloButtons(track.muted, track.soloed, { actions.toggleMute(i) }, { actions.toggleSolo(i) }, Modifier.width(MS_WIDTH))
        }
        if (expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(track.engine, style = mixText(9.sp, MixerInk.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                DbFaderRow(
                    db = track.levelDb,
                    onDbChange = { actions.setLevelDb(i, it) },
                    onReset = { actions.setLevelDb(i, 0f) },
                    index = i,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!inline) {
                    Sends(track, actions, Modifier.fillMaxWidth(), height = 30.dp, labelWidth = 34.dp, valueWidth = 52.dp)
                }
            }
        }
    }
}

/** Pan, R and D: side by side inline when [height] is null, else stacked at that height under an expanded row. */
@Composable
private fun Sends(
    track: MixerTrack,
    actions: AiMixerActions,
    modifier: Modifier,
    height: Dp? = null,
    labelWidth: Dp = 18.dp,
    valueWidth: Dp = 26.dp,
) {
    val i = track.index
    @Composable
    fun sliders(m: Modifier, h: Dp) {
        MixerSlider(
            "pan", track.pan, -1f..1f, { actions.setPan(i, it) }, ::panText, i, m,
            onReset = { actions.setPan(i, track.filePan) },
            labelWidth = labelWidth, valueWidth = valueWidth, height = h,
        )
        MixerSlider("R", track.reverb, 0f..1f, { actions.setReverb(i, it) }, ::f2, i, m, labelWidth = labelWidth, valueWidth = valueWidth, height = h)
        MixerSlider("D", track.delay, 0f..1f, { actions.setDelay(i, it) }, ::f2, i, m, labelWidth = labelWidth, valueWidth = valueWidth, height = h)
    }
    if (height == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(INLINE_SLIDER_GAP)) { sliders(modifier, SLIDER_HEIGHT) }
    } else {
        Column(modifier) { sliders(Modifier.fillMaxWidth(), height) }
    }
}

/** A read-only source: wave, meter and loudness, columns aligned with the tracks'. */
@Composable
private fun SourceRow(ch: Int, name: String, meters: State<MeterSnapshot>, inline: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    GridRow(Modifier.clip(shape).background(MixerInk.sourceCard).border(1.dp, MixerInk.line, shape)) {
        RowName(name, MixerInk.dim)
        MixerWave({ meters.waveOf(ch) }, MixerInk.dim, Modifier.weight(1f).height(22.dp))
        if (inline) Spacer(Modifier.width(INLINE_SLIDER_WIDTH * 3 + INLINE_SLIDER_GAP * 2))
        MixerMeter({ meters.fillOf(ch) }, Modifier.width(METER_WIDTH).height(6.dp), vertical = false)
        Spacer(Modifier.width(DB_WIDTH))
        LuText(meters, ch, Modifier.width(LU_WIDTH))
        Spacer(Modifier.width(MS_WIDTH))
    }
}

/** DRY or OUT: a full-width meter and its peak. */
@Composable
private fun BusRow(ch: Int, meters: State<MeterSnapshot>) {
    val shape = RoundedCornerShape(8.dp)
    GridRow(Modifier.clip(shape).background(MixerInk.sourceCard).border(1.dp, MixerInk.line, shape)) {
        RowName(BenchMeters.CHANNEL_NAMES[ch], MixerInk.text)
        MixerMeter({ meters.fillOf(ch) }, Modifier.weight(1f).height(6.dp), vertical = false)
        PeakText(meters, ch, Modifier.width(DB_WIDTH))
        Spacer(Modifier.width(LU_WIDTH + MS_WIDTH + ROW_GAP))
    }
}

@Composable
private fun GridRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        content = content,
    )
}

@Composable
private fun RowName(name: String, color: Color) {
    Text(
        name,
        style = mixText(11.sp, color, FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(NAME_WIDTH),
    )
}
