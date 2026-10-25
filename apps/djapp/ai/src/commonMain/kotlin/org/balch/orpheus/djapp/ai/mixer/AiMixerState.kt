package org.balch.orpheus.djapp.ai.mixer

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.djapp.ai.collab.EditOrigin
import org.balch.orpheus.djapp.ai.collab.HistoryChange
import org.balch.orpheus.djapp.ai.collab.HistoryItem
import org.balch.orpheus.djapp.ai.collab.HistoryPoint
import org.balch.orpheus.djapp.ai.collab.HistoryVibeEdit
import org.balch.orpheus.djapp.ai.collab.MixControl
import org.balch.orpheus.djapp.ai.collab.MixHistory
import org.balch.orpheus.djapp.ai.collab.PointKind
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
)

@Immutable
data class AiMixerUiState(
    val vibeName: String = "",
    /** Estimated USD the in-app AI has spent on this vibe, across runs; null before it spends anything. */
    val aiCostUsd: Double? = null,
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
) {
    val sectionScope: Boolean get() = scope == Scope.SECTION && sectionScopeAvailable && section >= 0

    /** One per captured row, so one per "+" line of [changeLines]. */
    val changeCount: Int get() = changes.size

    /** The changes History lists, for the header button: one edit can write several file values. */
    val historyChangeCount: Int get() = history.count { it.isChange }
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
    /** At or below the newest capture checkpoint, so it is saved in a capture file. */
    val captured: Boolean = false,
) : HistoryRow

@Immutable
data class PointRow(val id: Int, val label: String, val atMs: Long, val capture: Boolean = false) : HistoryRow

/** One edited vibe field on top of the file: [label] names it in words, [before] and [after] are short texts. */
@Immutable
data class VibeEditRow(
    val id: Int,
    val label: String,
    val before: String,
    val after: String,
    val who: String,
    val on: Boolean,
    /** At or below the newest capture checkpoint, so it is saved in a capture file. */
    val captured: Boolean,
    val atMs: Long,
) : HistoryRow

/** A mix change or a vibe field edit: what the timeline counts as a change. */
internal val HistoryRow.isChange: Boolean get() = this is ChangeRow || this is VibeEditRow

private val HistoryRow.isOn: Boolean get() = (this as? ChangeRow)?.on ?: (this as? VibeEditRow)?.on ?: false

/** The on changes above the newest capture row, or null when the timeline has no capture. */
fun uncapturedOn(rows: List<HistoryRow>): Int? {
    val at = rows.indexOfLast { it is PointRow && it.capture }
    if (at < 0) return null
    return rows.drop(at + 1).count { it.isOn }
}

/** The Now line after "Now": " · N uncaptured of M changes on" once a capture exists, else " · N of M changes on". */
fun nowDetail(rows: List<HistoryRow>, onCount: Int): String {
    val total = rows.count { it.isChange }
    val uncaptured = uncapturedOn(rows) ?: return " · $onCount of $total changes on"
    return " · $uncaptured uncaptured of $total changes on"
}

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
    return h.items.map { item -> historyRow(h, item, vibe, trackNames, ::writesOf) }
}

private val TRACK_ENGINE = Regex("""tracks\[(\d+)]\.engine(Edm|Space)\.(.+)""")
private val SECTION_OVERRIDE = Regex("""arrangement\.sections\[(\d+)]\.trackOverrides\.(\d+)\.(.+)""")

private val TRACK_FIELD = Regex("""tracks\[(\d+)]\.(.+)""")
private val SECTION_FIELD = Regex("""arrangement\.sections\[(\d+)]\.(.+)""")

/** A vibe field path in words: "TEXT · EDM engine", "chorus · KICK morph", "bridge · compingStyle", else the last two segments. */
internal fun vibePathLabel(path: String, vibe: Vibe?, trackNames: (Int) -> String): String {
    TRACK_ENGINE.matchEntire(path)?.let { m ->
        val (track, slot, field) = m.destructured
        return "${trackNames(track.toInt())} · ${if (slot == "Edm") "EDM" else "Space"} ${if (field == "engineId") "engine" else field}"
    }
    SECTION_OVERRIDE.matchEntire(path)?.let { m ->
        val (section, track, field) = m.destructured
        val name = vibe?.arrangement?.sections?.getOrNull(section.toInt())?.name ?: "section $section"
        return "$name · ${trackNames(track.toInt())} $field"
    }
    TRACK_FIELD.matchEntire(path)?.let { m ->
        return "${trackNames(m.groupValues[1].toInt())} · ${m.groupValues[2]}"
    }
    SECTION_FIELD.matchEntire(path)?.let { m ->
        val section = m.groupValues[1]
        val name = vibe?.arrangement?.sections?.getOrNull(section.toInt())?.name ?: "section $section"
        return "$name · ${m.groupValues[2]}"
    }
    return path.split('.').takeLast(2).joinToString(".")
}

/** A vibe field's value as the row shows it: numbers to 2 decimals (whole numbers as they are), enum names, counts for lists. */
internal fun shortJson(e: JsonElement): String = when (e) {
    JsonNull -> "(none)"
    is JsonPrimitive -> {
        val text = e.content
        val n = if (e.isString) null else text.toDoubleOrNull()
        when {
            n == null -> text
            n == round(n) && abs(n) < 1e15 -> n.toLong().toString()
            else -> f2(n.toFloat())
        }
    }
    is JsonArray -> "(${e.size} steps)"
    is JsonObject -> "(changed)"
}

private fun CaptureRow.sameField(o: CaptureRow) =
    scope == o.scope && section == o.section && track == o.track && field == o.field && macro == o.macro

private fun historyRow(
    h: MixHistory,
    item: HistoryItem,
    vibe: Vibe?,
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
            captured = h.isCaptured(item.id),
        )
        is HistoryPoint -> PointRow(item.id, item.label, item.atMs, item.kind == PointKind.CAPTURE)
        is HistoryVibeEdit -> VibeEditRow(
            id = item.id,
            label = vibePathLabel(item.path, vibe, trackNames),
            before = shortJson(item.before),
            after = shortJson(item.after),
            who = item.origin.who(),
            on = item.on,
            captured = h.isCaptured(item.id),
            atMs = item.atMs,
        )
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
