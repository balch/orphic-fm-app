package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToLong
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.HistoryChange
import org.balch.orpheus.djapp.ai.collab.HistoryItem
import org.balch.orpheus.djapp.ai.collab.HistoryPoint
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixHistory
import org.balch.orpheus.djapp.ai.collab.label
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.bench.CaptureField
import org.balch.orpheus.features.pulsar.bench.CaptureRow
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.capture
import org.balch.orpheus.features.pulsar.bench.effective
import org.balch.orpheus.features.pulsar.bench.macroView
import org.balch.orpheus.features.pulsar.bench.sectionScopeAvailable
import org.balch.orpheus.features.pulsar.bench.vibeDefault
import org.balch.orpheus.features.pulsar.models.Vibe

@Immutable
data class MixerTrack(
    val index: Int,
    val name: String,
    val engine: String,
    val levelDb: Float,
    val pan: Float,
    val filePan: Float,
    val reverb: Float,
    val delay: Float,
    val muted: Boolean,
    val soloed: Boolean,
    val silenced: Boolean,
)

@Immutable
data class MixerMacro(
    val macro: Macro,
    val value: Float,
    val resetValue: Float,
    val changed: Boolean,
    val note: String,
)

@Immutable
data class MixerCapture(
    val ok: Boolean,
    val message: String,
    val file: String?,
    val rows: Int,
    val stale: Boolean?,
    val appliedToAiVibe: Boolean,
)

@Immutable
data class AiMixerUiState(
    val vibeName: String = "",
    val sections: List<String> = emptyList(),
    val section: Int = -1,
    val loopSection: Int? = null,
    val sectionScopeAvailable: Boolean = false,
    val scope: Scope = Scope.VIBE,
    val tracks: List<MixerTrack> = emptyList(),
    val macros: List<MixerMacro> = emptyList(),
    val changes: List<CaptureRow> = emptyList(),
    val capture: MixerCapture? = null,
    val capturing: Boolean = false,
    val notice: String? = null,
    /** Tracks the user pinned; the agent may not change them. */
    val pins: Set<Int> = emptySet(),
    /** Tracks the agent changed this turn. */
    val agentTouched: Set<Int> = emptySet(),
    /** The History timeline, oldest first. */
    val history: List<HistoryRow> = emptyList(),
    /** The point being heard while its button is held. */
    val hearing: Int? = null,
    val onCount: Int = 0,
    /** When the vibe loaded, for the File row's age. */
    val loadedAtMs: Long = 0L,
    /** The vibe an agent or MCP apply replaced, which the timeline can roll back to; null after any other load. */
    val beforeApply: BeforeApplyRow? = null,
) {
    val sectionScope: Boolean get() = scope == Scope.SECTION && sectionScopeAvailable && section >= 0

    /** One per captured row, so one per "+" line of [changeLines]. */
    val changeCount: Int get() = changes.size

    /** The changes History lists, for the header button: one edit can write several file values. */
    val historyChangeCount: Int get() = history.count { it is ChangeRow }
}

/** The timeline's row under File: the vibe the apply replaced, with [changes] mix edits of its own. */
@Immutable
data class BeforeApplyRow(val vibeName: String, val who: String, val changes: Int, val atMs: Long)

fun beforeApplyRow(h: MixHistory): BeforeApplyRow? = h.beforeApply?.let {
    BeforeApplyRow(it.vibe.name, it.origin.who(), it.history.onCount, it.atMs)
}

/** One row of the History timeline. */
sealed interface HistoryRow

@Immutable
data class ChangeRow(
    val id: Int,
    val who: String,
    val label: String,
    val before: String,
    val after: String,
    val where: String,
    val atMs: Long,
    val on: Boolean,
    val covered: Boolean,
    /** The file values Capture writes because of this change: those that would differ with it off. */
    val writes: List<CaptureRow> = emptyList(),
) : HistoryRow

@Immutable
data class PointRow(val id: Int, val label: String, val atMs: Long) : HistoryRow

private fun EditOrigin.who() = when (this) {
    EditOrigin.USER -> "you"
    EditOrigin.AGENT -> "Orpheus"
    EditOrigin.MCP -> "Claude Code"
}

/** "TEXT · level": the track and the field, as the timeline names a change. */
private fun MixControl.historyLabel(trackNames: (Int) -> String): String {
    val full = label(trackNames)
    val t = track ?: return full
    val name = trackNames(t)
    return if (full.startsWith("$name ")) "$name · ${full.removePrefix("$name ")}" else full
}

fun historyRows(h: MixHistory, vibe: Vibe? = null, trackNames: (Int) -> String): List<HistoryRow> {
    // Capture writes Now, so a change's share is what Now's rows lose when it alone is off.
    val now = vibe?.let { capture(it, h.layers()) }.orEmpty()
    fun writesOf(c: HistoryChange): List<CaptureRow> {
        if (vibe == null || !c.on) return emptyList()
        val without = capture(vibe, h.toggle(c.id).layers())
        return now.filter { r -> without.none { it.sameField(r) && abs(it.new - r.new) <= 1e-4f } }
    }
    return h.items.map { item -> historyRow(h, item, trackNames, ::writesOf) }
}

private fun CaptureRow.sameField(o: CaptureRow) =
    scope == o.scope && section == o.section && track == o.track && field == o.field && macro == o.macro

private fun historyRow(
    h: MixHistory,
    item: HistoryItem,
    trackNames: (Int) -> String,
    writesOf: (HistoryChange) -> List<CaptureRow>,
): HistoryRow =
    when (item) {
        is HistoryChange -> ChangeRow(
            id = item.id,
            who = item.origin.who(),
            label = item.control.historyLabel(trackNames),
            before = item.before,
            after = item.after,
            where = item.sectionName ?: "whole vibe",
            atMs = item.atMs,
            on = item.on,
            covered = h.isCovered(item.id),
            writes = writesOf(item),
        )
        is HistoryPoint -> PointRow(item.id, item.label, item.atMs)
    }

/** The header's changes button text. */
fun changesLabel(count: Int): String = when (count) {
    0 -> "No changes"
    1 -> "1 change"
    else -> "$count changes"
}

/** The bench page's state view, for the panel. [knobs] are the app's macro knobs. */
fun mixerState(
    vibe: Vibe,
    section: Int,
    layers: BenchLayers,
    scope: Scope,
    muted: Set<Int>,
    soloed: Set<Int>,
    loopSection: Int?,
    knobs: Map<Macro, Float>,
): AiMixerUiState {
    val sectionScopeAvailable = vibe.sectionScopeAvailable()
    val inSection = scope == Scope.SECTION && sectionScopeAvailable && section >= 0
    val tracks = vibe.tracks.indices.map { t ->
        val track = vibe.tracks[t]
        val e = layers.effective(vibe, section, t, knobs[Macro.ENERGY] ?: vibe.energy)
        MixerTrack(
            index = t,
            name = BenchMeters.CHANNEL_NAMES.getOrElse(t) { "T$t" },
            engine = "${track.engineEdm.engineId.name}/${track.engineSpace.engineId.name}",
            levelDb = e.levelDb,
            pan = e.pan,
            filePan = track.pan,
            reverb = e.reverbSend,
            delay = e.delaySend,
            muted = t in muted,
            soloed = t in soloed,
            silenced = t in muted || (soloed.isNotEmpty() && t !in soloed),
        )
    }
    val macros = Macro.entries.map { m ->
        val mv = layers.macroView(vibe, section, m, knobs[m] ?: m.vibeDefault(vibe))
        val changed = if (inSection) mv.heardMoved else mv.knobMoved
        MixerMacro(
            macro = m,
            value = if (inSection) mv.heard else mv.knob,
            resetValue = if (inSection) min(1f, mv.file * mv.mult) else mv.file,
            changed = changed,
            note = when {
                inSection -> "heard here"
                abs(mv.mult - 1f) > 1e-3f -> "heard ${f2(mv.heard)} (x${f2(mv.mult)})"
                !changed && abs(mv.live - mv.file) > 1e-3f -> "app knob; file ${f2(mv.file)}"
                else -> ""
            },
        )
    }
    return AiMixerUiState(
        vibeName = vibe.name,
        sections = vibe.arrangement?.sections.orEmpty().map { it.name },
        section = section,
        loopSection = loopSection,
        sectionScopeAvailable = sectionScopeAvailable,
        scope = scope,
        tracks = tracks,
        macros = macros,
        changes = capture(vibe, layers),
    )
}

enum class ChangeKind { HEADER, OLD, NEW, FLAG }

data class ChangeLine(val kind: ChangeKind, val text: String)

private val FIELD = mapOf(
    CaptureField.VOLUME to "volume (edm)",
    CaptureField.VOLUME_SPACE to "volume (space)",
    CaptureField.PAN to "pan",
    CaptureField.REVERB_SEND to "reverbSend",
    CaptureField.REVERB_SEND_SPACE to "reverbSend (space)",
    CaptureField.DELAY_SEND to "delaySend",
    CaptureField.DELAY_SEND_SPACE to "delaySend (space)",
)

private fun fieldName(r: CaptureRow): String {
    val macro = r.macro?.name?.lowercase().orEmpty()
    return when (r.field) {
        CaptureField.MACRO -> macro + if (r.scope == Scope.SECTION) " multiplier" else ""
        CaptureField.MACRO_RAMP -> "$macro rampTo"
        CaptureField.DELAY_TIME_A -> if (r.scope == Scope.SECTION) "delay.timeA" else "effects.delayTimeA"
        CaptureField.DELAY_TIME_B -> if (r.scope == Scope.SECTION) "delay.timeB" else "effects.delayTimeB"
        CaptureField.DELAY_FEEDBACK -> if (r.scope == Scope.SECTION) "delay.feedback" else "effects.delayFeedback"
        else -> FIELD.getValue(r.field)
    }
}

/** Display text for one changes row, as bench.js `renderSide` writes it: header, "- old" and "+ new" lines, and flags. */
fun changeLines(rows: List<CaptureRow>): List<ChangeLine> {
    val out = mutableListOf<ChangeLine>()
    var last = ""
    var spaceWarn = false
    for (r in rows) {
        val where = if (r.scope == Scope.VIBE) "vibe" else "section \"${r.sectionName}\""
        val head = "// $where" + (r.track?.let { " · ${BenchMeters.CHANNEL_NAMES.getOrElse(it) { "T$it" }}" } ?: "")
        if (head != last) {
            out += ChangeLine(ChangeKind.HEADER, head)
            last = head
        }
        val f = fieldName(r)
        out += ChangeLine(ChangeKind.OLD, "- $f = ${fixed(r.old, 3)}")
        var add = "+ $f = ${fixed(r.new, 3)}"
        if ((r.field == CaptureField.VOLUME || r.field == CaptureField.VOLUME_SPACE) && r.old > 0f && r.new > 0f) {
            val d = 20.0 * log10((r.new / r.old).toDouble())
            add += "  (" + (if (d > 0) "+" else "") + fixed(d.toFloat(), 1) + " dB)"
        }
        out += ChangeLine(ChangeKind.NEW, add)
        if (r.clampedDb > 0f) {
            out += ChangeLine(
                ChangeKind.FLAG,
                "clamped: written value is ${fixed(r.clampedDb, 1)} dB quieter than heard; cut the other tracks by that much instead",
            )
        }
        if (r.scope == Scope.SECTION && r.macro == Macro.SPACE) spaceWarn = true
    }
    if (spaceWarn) {
        out += ChangeLine(ChangeKind.FLAG, "A section Space change also scales that section's sends (Kotlin applies Space to sends).")
    }
    return out
}

internal fun f2(x: Float) = fixed(x, 2)

/** JS `toFixed` for the small ranges shown here; multi-platform, so no String.format. */
internal fun fixed(x: Float, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(x.toDouble()) * scale).roundToLong()
    val neg = x < 0f && scaled != 0L
    val whole = scaled / scale
    val frac = (scaled % scale).toString().padStart(decimals, '0')
    return (if (neg) "-" else "") + whole + "." + frac
}
