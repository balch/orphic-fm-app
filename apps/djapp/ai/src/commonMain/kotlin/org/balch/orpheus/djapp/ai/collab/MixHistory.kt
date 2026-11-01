package org.balch.orpheus.djapp.ai.collab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.balch.orpheus.features.pulsar.bench.diffTree
import org.balch.orpheus.features.pulsar.bench.patched
import org.balch.orpheus.features.pulsar.bench.toTree
import org.balch.orpheus.features.pulsar.bench.withMixerFieldsOf
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.LayerWrite
import org.balch.orpheus.features.pulsar.bench.valueOf
import org.balch.orpheus.features.pulsar.bench.with
import org.balch.orpheus.features.pulsar.models.Vibe

/** One row of the Mixer's History timeline: a change or a marked point. */
@Serializable
sealed interface HistoryItem {
    val id: Int
    val atMs: Long
}

/** One Mixer edit: the layer fields it wrote and the text the Mixer showed. Off changes are skipped in replay. */
@Serializable
@SerialName("change")
data class HistoryChange(
    override val id: Int,
    override val atMs: Long,
    val origin: EditOrigin,
    val control: MixControl,
    val section: Int?,
    val sectionName: String?,
    val before: String,
    val after: String,
    val writes: Map<LayerField, LayerWrite>,
    val updatedMs: Long,
    val on: Boolean = true,
) : HistoryItem

/** One edited vibe field on top of the vibe's file: [path] as in `VibeFieldChange`, with the values either side. */
@Serializable
@SerialName("vibe")
data class HistoryVibeEdit(
    override val id: Int,
    override val atMs: Long,
    val origin: EditOrigin,
    val path: String,
    val before: JsonElement,
    val after: JsonElement,
    val updatedMs: Long,
    val on: Boolean = true,
) : HistoryItem

/** A user's ◆ Mark, or a CAPTURE the mix was saved at. */
@Serializable
enum class PointKind { MARK, CAPTURE }

/** A point in the timeline that the mix can be previewed at or rolled back to; a CAPTURE names its saved [captureFile]. */
@Serializable
@SerialName("point")
data class HistoryPoint(
    override val id: Int,
    override val atMs: Long,
    val label: String,
    val kind: PointKind = PointKind.MARK,
    val captureFile: String? = null,
) : HistoryItem

/** The Mixer's edit timeline. The bench layers are always the replay of the changes that are on. */
data class MixHistory(
    val items: List<HistoryItem> = emptyList(),
    val nextId: Int = 1,
    val pointCount: Int = 0,
    /** When the vibe this history belongs to loaded. */
    val loadedAtMs: Long = 0L,
) {
    val changes: List<HistoryChange> get() = items.filterIsInstance<HistoryChange>()
    val onCount: Int get() = changes.count { it.on }

    /** Index in [items] of the newest CAPTURE point, or -1. */
    val newestCaptureIndex: Int
        get() = items.indexOfLast { it is HistoryPoint && it.kind == PointKind.CAPTURE }

    /** The changes above the newest capture, the ones a capture would still save. */
    val uncaptured: List<HistoryChange> get() = items.drop(newestCaptureIndex + 1).filterIsInstance<HistoryChange>()

    val vibeEdits: List<HistoryVibeEdit> get() = items.filterIsInstance<HistoryVibeEdit>()

    /** Mix changes and vibe rows above the newest capture, what a capture would still save. */
    val uncapturedCount: Int
        get() = items.drop(newestCaptureIndex + 1).count { it is HistoryChange || it is HistoryVibeEdit }

    /** True when the change sits at or below the newest capture, so it is frozen. */
    fun isCaptured(id: Int): Boolean {
        val i = items.indexOfFirst { it.id == id }
        return i in 0..newestCaptureIndex
    }

    /**
     * Adds a change. An edit of the same control and section above the newest point (Mark or capture) merges into that
     * row, whoever made it; a row that merges back to where it began is dropped. An off row never merges.
     */
    fun record(
        origin: EditOrigin,
        control: MixControl,
        section: Int?,
        sectionName: String?,
        before: String,
        after: String,
        writes: Map<LayerField, LayerWrite>,
        nowMs: Long,
    ): MixHistory {
        if (writes.isEmpty()) return this
        // Folding across a point would change what that point plays and what rolling back to it keeps.
        val from = items.indexOfLast { it is HistoryPoint } + 1
        val at = (from until items.size).lastOrNull { i ->
            val c = items[i] as? HistoryChange
            c != null && c.on && c.control == control && c.section == section && c.writes.keys == writes.keys
        }
        if (at != null) {
            val old = items[at] as HistoryChange
            val merged = old.writes.mapValues { (f, w) -> LayerWrite(w.before, writes.getValue(f).after) }
            val rest = items.filterIndexed { i, _ -> i != at }
            if (merged.values.all { it.before == it.after }) return copy(items = rest)
            val folded = old.copy(origin = origin, after = after, writes = merged, updatedMs = nowMs)
            return copy(items = rest + folded)
        }
        val change = HistoryChange(
            id = nextId, atMs = nowMs, origin = origin, control = control, section = section,
            sectionName = sectionName, before = before, after = after, writes = writes, updatedMs = nowMs,
        )
        return copy(items = items + change, nextId = nextId + 1)
    }

    /**
     * Folds [edited], the vibe now playing, into the vibe rows above the newest point. Rows are one per changed field
     * against [file] plus the rows below that point; mixer fields are never rows. Off rows are never touched.
     */
    fun recordVibe(origin: EditOrigin, file: Vibe, edited: Vibe, nowMs: Long): MixHistory {
        val from = items.indexOfLast { it is HistoryPoint } + 1
        val base = file.patched(items.take(from).onVibeRows().map { it.path to it.after })
        val diff = diffTree(base.toTree(), edited.withMixerFieldsOf(base).toTree()).associateBy { it.path }
        val kept = mutableListOf<HistoryItem>()
        val refolded = mutableListOf<HistoryVibeEdit>()
        for ((i, item) in items.withIndex()) {
            if (i < from || item !is HistoryVibeEdit || !item.on) { kept += item; continue }
            val d = diff[item.path] ?: continue
            if (d.new == item.after) kept += item
            else refolded += item.copy(origin = origin, after = d.new, updatedMs = nowMs)
        }
        val have = (kept.drop(from) + refolded).filterIsInstance<HistoryVibeEdit>().filter { it.on }.map { it.path }.toSet()
        var id = nextId
        val added = diff.values.filter { it.path !in have }.map {
            HistoryVibeEdit(id++, nowMs, origin, it.path, it.old, it.new, nowMs)
        }
        return copy(items = kept + refolded + added, nextId = id)
    }

    /** The file with the on vibe rows applied in order. */
    fun effective(file: Vibe): Vibe = file.withRows(items)

    /** The vibe as it stood at the point: the on vibe rows before it. [FILE] gives [file]. */
    fun effectiveAt(file: Vibe, pointId: Int): Vibe {
        if (pointId == FILE) return file
        val i = items.indexOfFirst { it.id == pointId }
        return if (i < 0) effective(file) else file.withRows(items.take(i))
    }

    // No rows gives the file itself, not its JSON round trip, so callers can compare it with the vibe playing.
    private fun Vibe.withRows(list: List<HistoryItem>): Vibe {
        val rows = list.onVibeRows()
        return if (rows.isEmpty()) this else patched(rows.map { it.path to it.after })
    }

    private fun List<HistoryItem>.onVibeRows() = filterIsInstance<HistoryVibeEdit>().filter { it.on }

    /** Appends a CAPTURE point that names the saved [file]; it does not count as a Mark. */
    fun capture(nowMs: Long, label: String, file: String?): MixHistory = copy(
        items = items + HistoryPoint(nextId, nowMs, label, PointKind.CAPTURE, file),
        nextId = nextId + 1,
    )

    fun toggle(id: Int): MixHistory =
        copy(
            items = items.map {
                when {
                    it.id != id -> it
                    it is HistoryChange -> it.copy(on = !it.on)
                    it is HistoryVibeEdit -> it.copy(on = !it.on)
                    else -> it
                }
            },
        )

    /** Marks a point; a null [label] names it "Point N" in order. */
    fun mark(nowMs: Long, label: String? = null): MixHistory = copy(
        items = items + HistoryPoint(nextId, nowMs, label ?: "Point ${pointCount + 1}"),
        nextId = nextId + 1,
        pointCount = pointCount + 1,
    )

    /** Drops everything after the point; [FILE] drops every item. */
    fun rollBackTo(pointId: Int): MixHistory {
        if (pointId == FILE) return copy(items = emptyList())
        val i = items.indexOfFirst { it.id == pointId }
        return if (i < 0) this else copy(items = items.take(i + 1))
    }

    /**
     * After a reset: keeps each write on a [pinned] track whose field [layers] still agrees with the replay, then
     * drops emptied changes and all points. An off change stays only whole, so its dot never re-applies a cleared value.
     */
    fun keepOnly(layers: BenchLayers, pinned: Set<Int>): MixHistory {
        val replay = layers()
        val kept = changes.mapNotNull { c ->
            val writes = c.writes.filterKeys { f -> f.track in pinned && layers.valueOf(f) == replay.valueOf(f) }
            if (writes.isEmpty() || (!c.on && writes.size < c.writes.size)) null else c.copy(writes = writes)
        }
        return copy(items = kept)
    }

    fun layers(): BenchLayers = replay(items)

    /** The mix as it stood at the point: the on changes before it. [FILE] gives the written mix. */
    fun layersAt(pointId: Int): BenchLayers {
        if (pointId == FILE) return BenchLayers()
        val i = items.indexOfFirst { it.id == pointId }
        return if (i < 0) layers() else replay(items.take(i))
    }

    /** True when a later on change writes every field of this one, so turning this one off changes nothing heard. */
    fun isCovered(id: Int): Boolean {
        val i = items.indexOfFirst { it.id == id }
        val change = items.getOrNull(i) as? HistoryChange ?: return false
        val later = items.drop(i + 1).filterIsInstance<HistoryChange>().filter { it.on }
        return change.writes.keys.all { f -> later.any { f in it.writes } }
    }

    private fun replay(list: List<HistoryItem>): BenchLayers =
        list.filterIsInstance<HistoryChange>().filter { it.on }
            .fold(BenchLayers()) { acc, c -> c.writes.entries.fold(acc) { l, (f, w) -> l.with(f, w.after) } }

    companion object {
        /** The id of the written file state, before any change. */
        const val FILE = 0
    }
}
