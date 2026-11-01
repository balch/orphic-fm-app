package org.balch.orpheus.djapp.ai.collab

import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.LayerWrite
import org.balch.orpheus.features.pulsar.bench.valueOf
import org.balch.orpheus.features.pulsar.bench.with
import org.balch.orpheus.features.pulsar.models.Vibe

/** One row of the Mixer's History timeline: a change or a marked point. */
sealed interface HistoryItem {
    val id: Int
    val atMs: Long
}

/** One Mixer edit: the layer fields it wrote and the text the Mixer showed. Off changes are skipped in replay. */
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

/** A marked point in the timeline that the mix can be previewed at or rolled back to. */
data class HistoryPoint(
    override val id: Int,
    override val atMs: Long,
    val label: String,
) : HistoryItem

/**
 * What an agent's or MCP's apply replaced: the vibe that played and its whole timeline, so a rollback can bring both
 * back. Its [history] can hold an earlier [BeforeApply] of its own, so applies undo one at a time.
 */
data class BeforeApply(
    val vibe: Vibe,
    val history: MixHistory,
    val origin: EditOrigin,
    val atMs: Long,
)

/** The Mixer's edit timeline. The bench layers are always the replay of the changes that are on. */
data class MixHistory(
    val items: List<HistoryItem> = emptyList(),
    val nextId: Int = 1,
    val pointCount: Int = 0,
    /** When the vibe this history belongs to loaded. */
    val loadedAtMs: Long = 0L,
    /** Set when an agent or MCP apply loaded this vibe: what it replaced, below [FILE] in the timeline. */
    val beforeApply: BeforeApply? = null,
) {
    val changes: List<HistoryChange> get() = items.filterIsInstance<HistoryChange>()
    val onCount: Int get() = changes.count { it.on }

    /**
     * Adds a change, folding it into the last one when a drag of the same control by the same origin continues.
     * A drag that folds back to where it began is dropped.
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
        val last = items.lastOrNull() as? HistoryChange
        if (last != null && last.on && last.origin == origin && last.control == control &&
            last.section == section && last.writes.keys == writes.keys && nowMs - last.updatedMs <= FOLD_MS
        ) {
            val merged = last.writes.mapValues { (f, w) -> LayerWrite(w.before, writes.getValue(f).after) }
            if (merged.values.all { it.before == it.after }) return copy(items = items.dropLast(1))
            val folded = last.copy(after = after, writes = merged, updatedMs = nowMs)
            return copy(items = items.dropLast(1) + folded)
        }
        val change = HistoryChange(
            id = nextId, atMs = nowMs, origin = origin, control = control, section = section,
            sectionName = sectionName, before = before, after = after, writes = writes, updatedMs = nowMs,
        )
        return copy(items = items + change, nextId = nextId + 1)
    }

    fun toggle(id: Int): MixHistory =
        copy(items = items.map { if (it is HistoryChange && it.id == id) it.copy(on = !it.on) else it })

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
        /** The id of [beforeApply]: the vibe and mix the apply replaced. */
        const val BEFORE_APPLY = -1
        const val FOLD_MS = 2000L
    }
}
