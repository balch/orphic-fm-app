package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.LayerField
import org.balch.orpheus.features.pulsar.bench.LayerWrite
import org.balch.orpheus.features.pulsar.bench.Macro
import org.balch.orpheus.features.pulsar.bench.diff
import org.balch.orpheus.features.pulsar.bench.with
import org.balch.orpheus.features.pulsar.bench.valueOf

class MixHistoryTest {
    private fun MixHistory.rec(
        control: MixControl,
        field: LayerField,
        before: Float?,
        after: Float?,
        at: Long = 0L,
        origin: EditOrigin = EditOrigin.USER,
    ) = record(origin, control, null, null, "b", "a", mapOf(field to LayerWrite(before, after)), at)

    private val level2 = LayerField.VibeLevel(2)
    private val pan5 = LayerField.VibePan(5)

    @Test
    fun replayPlaysOnlyOnChanges() {
        val h = MixHistory()
            .rec(MixControl.Level(2), level2, null, -2f)
            .rec(MixControl.Pan(5), pan5, null, 0.4f, at = 10_000L)
        val off = h.toggle(h.changes.first().id)
        val layers = off.layers()
        assertEquals(0.4f, layers.valueOf(pan5))
        assertNull(layers.valueOf(level2))
    }

    @Test
    fun dragsFoldWithinTwoSeconds() {
        val c = MixControl.Level(2)
        val h = MixHistory()
            .rec(c, level2, null, -1f, at = 0)
            .rec(c, level2, -1f, -2f, at = 900)
            .rec(c, level2, -2f, -3f, at = 1800)
        assertEquals(1, h.changes.size)
        assertEquals(LayerWrite(null, -3f), h.changes.single().writes.getValue(level2))
        assertEquals(1800L, h.changes.single().updatedMs)
        assertEquals(0L, h.changes.single().atMs)
        val h2 = h.rec(c, level2, -3f, -4f, at = 4000)
        assertEquals(2, h2.changes.size)
    }

    @Test
    fun differentOriginNeverFolds() {
        val c = MixControl.Level(2)
        val h = MixHistory()
            .rec(c, level2, null, -1f, at = 0, origin = EditOrigin.USER)
            .rec(c, level2, -1f, -2f, at = 500, origin = EditOrigin.AGENT)
        assertEquals(2, h.changes.size)
    }

    @Test
    fun coveredChangeRevealsWhenLaterTurnsOff() {
        val c = MixControl.Level(2)
        val h = MixHistory()
            .rec(c, level2, null, -2f, at = 0)
            .rec(c, level2, -2f, -6f, at = 10_000)
        val first = h.changes[0]
        val second = h.changes[1]
        assertTrue(h.isCovered(first.id))
        assertFalse(h.isCovered(second.id))
        assertEquals(-2f, h.toggle(second.id).layers().valueOf(level2))
    }

    @Test
    fun layersAtPointIgnoresLaterChanges() {
        val h = MixHistory()
            .rec(MixControl.Level(2), level2, null, -2f)
            .mark(5_000L)
            .rec(MixControl.Pan(5), pan5, null, 0.4f, at = 10_000L)
        val point = h.items.filterIsInstance<HistoryPoint>().single()
        val at = h.layersAt(point.id)
        assertEquals(-2f, at.valueOf(level2))
        assertNull(at.valueOf(pan5))
        assertEquals(BenchLayers(), h.layersAt(MixHistory.FILE))
    }

    @Test
    fun rollBackDropsEverythingAfterThePoint() {
        val h = MixHistory()
            .rec(MixControl.Level(2), level2, null, -2f)
            .mark(5_000L)
            .rec(MixControl.Pan(5), pan5, null, 0.4f, at = 10_000L)
            .rec(MixControl.Level(3), LayerField.VibeLevel(3), null, -1f, at = 20_000L)
        val point = h.items.filterIsInstance<HistoryPoint>().single()
        val rolled = h.rollBackTo(point.id)
        assertEquals(h.items.take(2), rolled.items)
        assertTrue(h.rollBackTo(MixHistory.FILE).items.isEmpty())
    }

    @Test
    fun markNamesPointsInOrder() {
        val h = MixHistory().mark(1L).mark(2L).mark(3L, "Captured")
        val labels = h.items.filterIsInstance<HistoryPoint>().map { it.label }
        assertEquals(listOf("Point 1", "Point 2", "Captured"), labels)
    }

    @Test
    fun keepOnlyDropsResetFieldsAndPoints() {
        val l4 = LayerField.VibeLevel(4)
        val h = MixHistory()
            .rec(MixControl.Level(2), level2, null, -2f)
            .rec(MixControl.Level(4), l4, null, -3f, at = 10_000L)
            .mark(20_000L)
        val kept = h.keepOnly(BenchLayers().with(l4, -3f), pinned = setOf(4))
        assertEquals(1, kept.changes.size)
        assertTrue(l4 in kept.changes.single().writes)
        assertTrue(kept.items.none { it is HistoryPoint })
    }

    @Test
    fun keepOnlyDropsAnOffChangeOnAnUnpinnedStrip() {
        val h = MixHistory().rec(MixControl.Level(2), level2, null, -2f)
        val off = h.toggle(h.changes.single().id)
        assertTrue(off.keepOnly(BenchLayers(), pinned = emptySet()).changes.isEmpty())
        assertEquals(off.changes, off.keepOnly(BenchLayers(), pinned = setOf(2)).changes)
    }

    @Test
    fun keepOnlyDropsOffMacroChanges() {
        val energy = LayerField.VibeMacro(Macro.ENERGY)
        val h = MixHistory().rec(MixControl.MacroKnob(Macro.ENERGY), energy, null, 0.7f)
        val off = h.toggle(h.changes.single().id)
        assertTrue(off.keepOnly(BenchLayers(), pinned = setOf(0, 1, 2)).changes.isEmpty())
    }

    @Test
    fun aDragBackToItsStartIsDropped() {
        val c = MixControl.Level(2)
        val h = MixHistory()
            .rec(c, level2, null, -1f, at = 0)
            .rec(c, level2, -1f, null, at = 500)
        assertTrue(h.changes.isEmpty())
        assertEquals(BenchLayers(), h.layers())
        assertEquals(1, h.rec(c, level2, null, -3f, at = 900).changes.size)
    }

    @Test
    fun replayOfDiffsRebuildsLayers() {
        val first = BenchLayers()
            .with(LayerField.VibeLevel(1), -2f)
            .with(LayerField.VibePan(3), -0.5f)
            .with(LayerField.SectionLevel(2, 4), -4f)
        val second = first
            .with(LayerField.VibeMacro(Macro.ENERGY), 0.7f)
            .with(LayerField.VibeLevel(1), -5f)
            .with(LayerField.SectionReverb(2, 4), 0.3f)
        val h = MixHistory()
            .record(EditOrigin.USER, MixControl.Level(1), null, null, "", "", BenchLayers().diff(first), 0L)
            .record(EditOrigin.USER, MixControl.Pan(3), null, null, "", "", first.diff(second), 10_000L)
        assertEquals(second, h.layers())
    }
}
