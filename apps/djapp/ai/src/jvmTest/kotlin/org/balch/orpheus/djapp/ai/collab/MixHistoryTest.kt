package org.balch.orpheus.djapp.ai.collab

import kotlin.test.Test
import org.balch.orpheus.djapp.ai.tools.vibe
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.DelayFx
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
    fun repeatEditAboveCaptureFoldsIntoOneRow() {
        val h = MixHistory()
            .rec(MixControl.Level(2), level2, null, -3f, at = 0)
            .rec(MixControl.Level(3), LayerField.VibeLevel(3), null, -1f, at = 10_000)
            .rec(MixControl.Level(2), level2, -3f, -6f, at = 20_000)
        assertEquals(listOf(MixControl.Level(3), MixControl.Level(2)), h.changes.map { it.control })
        assertEquals(LayerWrite(null, -6f), h.changes.last().writes.getValue(level2))
        assertEquals(1, h.changes.last().id)
    }

    @Test
    fun aMarkStopsTheFold() {
        val c = MixControl.Level(2)
        val h = MixHistory().rec(c, level2, null, -3f, at = 0).mark(5_000)
            .rec(c, level2, -3f, -6f, at = 10_000)
        val mark = h.items.filterIsInstance<HistoryPoint>().single()
        assertEquals(2, h.changes.size)
        assertEquals(mark, h.items[1])
        assertEquals(-3f, h.layersAt(mark.id).valueOf(level2))
        val back = h.rollBackTo(mark.id)
        assertEquals(listOf(h.changes[0]), back.changes)
        assertEquals(-3f, back.layers().valueOf(level2))
    }

    @Test
    fun editFoldingBackToStartIsDropped() {
        val c = MixControl.Level(2)
        val h = MixHistory().rec(c, level2, null, -3f, at = 0).rec(c, level2, -3f, null, at = 10_000)
        assertTrue(h.changes.isEmpty())
    }

    @Test
    fun captureFreezesRowsBelowIt() {
        val c = MixControl.Level(2)
        val h = MixHistory().rec(c, level2, null, -3f, at = 0).capture(5_000, "Capture 1", "a.json")
            .rec(c, level2, -3f, -6f, at = 10_000)
        assertEquals(2, h.changes.size)
        assertTrue(h.isCaptured(h.changes[0].id))
        assertFalse(h.isCaptured(h.changes[1].id))
        assertEquals(1, h.newestCaptureIndex)
        assertEquals(listOf(h.changes[1]), h.uncaptured)
        assertEquals(0, h.pointCount)
    }

    @Test
    fun offRowIsNeverFolded() {
        val c = MixControl.Level(2)
        val one = MixHistory().rec(c, level2, null, -3f, at = 0)
        val h = one.toggle(one.changes.single().id).rec(c, level2, null, -6f, at = 10_000)
        assertEquals(2, h.changes.size)
    }

    @Test
    fun foldIgnoresOrigin() {
        val c = MixControl.Level(2)
        val h = MixHistory()
            .rec(c, level2, null, -1f, at = 0, origin = EditOrigin.USER)
            .rec(c, level2, -1f, -2f, at = 500, origin = EditOrigin.AGENT)
        assertEquals(1, h.changes.size)
        assertEquals(EditOrigin.AGENT, h.changes.single().origin)
    }

    @Test
    fun rollBackToCaptureDropsRowsAbove() {
        val c = MixControl.Level(2)
        val h = MixHistory().rec(c, level2, null, -3f, at = 0).capture(5_000, "Capture 1", "a.json")
            .rec(c, level2, -3f, -6f, at = 10_000)
        val cap = h.items.filterIsInstance<HistoryPoint>().single()
        assertEquals(PointKind.CAPTURE, cap.kind)
        assertEquals("a.json", cap.captureFile)
        assertEquals(h.items.take(2), h.rollBackTo(cap.id).items)
    }

    @Test
    fun layersAtCaptureIsMixAtCapture() {
        val c = MixControl.Level(2)
        val h = MixHistory().rec(c, level2, null, -3f, at = 0).capture(5_000, "Capture 1", null)
            .rec(c, level2, -3f, -6f, at = 10_000)
        val cap = h.items.filterIsInstance<HistoryPoint>().single()
        assertEquals(-3f, h.layersAt(cap.id).valueOf(level2))
    }

    @Test
    fun historyRoundTripsThroughJson() {
        fun change(i: Int, control: MixControl, field: LayerField) = HistoryChange(
            id = i, atMs = i * 10L, origin = EditOrigin.AGENT, control = control, section = null, sectionName = "x",
            before = "b", after = "a", writes = mapOf(field to LayerWrite(null, 0.5f)), updatedMs = i * 10L,
            on = i % 2 == 0,
        )
        val items: List<HistoryItem> = listOf(
            change(1, MixControl.Level(1), LayerField.VibeLevel(1)),
            change(2, MixControl.Pan(1), LayerField.VibePan(1)),
            change(3, MixControl.Send(1, true), LayerField.SectionReverb(2, 1)),
            change(4, MixControl.Send(1, false), LayerField.SectionDelay(2, 1)),
            change(5, MixControl.Mute(1), LayerField.VibeLevel(1)),
            change(6, MixControl.Solo(1), LayerField.VibeLevel(1)),
            change(7, MixControl.MacroKnob(Macro.ENERGY), LayerField.VibeMacro(Macro.ENERGY)),
            change(8, MixControl.DelaySetting(DelayFx.TIME_A), LayerField.VibeDelayFx(DelayFx.TIME_A)),
            change(9, MixControl.Section, LayerField.SectionMacro(1, Macro.ENERGY)),
            change(10, MixControl.Loop, LayerField.SectionDelayFx(1, DelayFx.FEEDBACK)),
            change(11, MixControl.EditScope, LayerField.VibeDelay(1)),
            change(12, MixControl.ClearAll, LayerField.VibeReverb(1)),
            HistoryPoint(13, 130L, "Point 1"),
            HistoryPoint(14, 140L, "Capture 1", PointKind.CAPTURE, "a.json"),
        )
        val json = Json { allowStructuredMapKeys = true }
        val text = json.encodeToString(ListSerializer(HistoryItem.serializer()), items)
        assertEquals(items, json.decodeFromString(ListSerializer(HistoryItem.serializer()), text))
    }

    @Test
    fun coveredChangeRevealsWhenLaterTurnsOff() {
        val c = MixControl.Level(2)
        // The capture freezes the first row, so the second is a separate, covering row.
        val h = MixHistory()
            .rec(c, level2, null, -2f, at = 0)
            .capture(5_000, "Capture 1", null)
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

    private val file: Vibe = vibe("File").let { v ->
        v.copy(tracks = v.tracks.toMutableList().also { it[0] = it[0].copy(role = TrackRole.Melodic()) })
    }

    private fun num(v: Float) = JsonPrimitive(v)

    private fun MixHistory.edit(edited: Vibe, at: Long = 0L, origin: EditOrigin = EditOrigin.AGENT) =
        recordVibe(origin, file, edited, at)

    @Test
    fun vibeEditRecordsOneRowPerField() {
        val h = MixHistory().edit(file.copy(bpm = 130f, seed = 7))
        assertEquals(listOf("bpm", "seed"), h.vibeEdits.map { it.path })
        val bpm = h.vibeEdits.first()
        assertEquals(num(120f), bpm.before)
        assertEquals(num(130f), bpm.after)
        assertTrue(bpm.on)
    }

    @Test
    fun secondEditFoldsSamePathKeepingIdAndBefore() {
        val h = MixHistory().edit(file.copy(bpm = 130f), at = 1_000).edit(file.copy(bpm = 140f), at = 2_000, origin = EditOrigin.USER)
        val row = h.vibeEdits.single()
        assertEquals(1, row.id)
        assertEquals(num(120f), row.before)
        assertEquals(num(140f), row.after)
        assertEquals(1_000L, row.atMs)
        assertEquals(2_000L, row.updatedMs)
        assertEquals(EditOrigin.USER, row.origin)
    }

    @Test
    fun unchangedRowKeepsItsPlace() {
        val h = MixHistory().edit(file.copy(bpm = 130f), at = 1_000).edit(file.copy(bpm = 130f, seed = 7), at = 2_000)
        assertEquals(listOf("bpm", "seed"), h.vibeEdits.map { it.path })
        assertEquals(1_000L, h.vibeEdits.first().updatedMs)
    }

    @Test
    fun editBackToFileDropsRow() {
        val h = MixHistory().edit(file.copy(bpm = 130f)).edit(file)
        assertTrue(h.vibeEdits.isEmpty())
    }

    @Test
    fun foldStopsAtPoint() {
        val h = MixHistory().edit(file.copy(bpm = 130f)).capture(5_000, "Capture 1", "a.json")
            .recordVibe(EditOrigin.AGENT, file, file.copy(bpm = 140f), 6_000)
        assertEquals(2, h.vibeEdits.size)
        assertEquals(num(130f), h.vibeEdits[0].after)
        assertEquals(num(130f), h.vibeEdits[1].before)
        assertEquals(num(140f), h.vibeEdits[1].after)
    }

    @Test
    fun granularityFlipReplacesWholeRow() {
        val whole = file.copy(tracks = file.tracks.toMutableList().also { it[0] = it[0].copy(role = TrackRole.Chordal()) })
        val leaf = file.copy(
            tracks = file.tracks.toMutableList().also { it[0] = it[0].copy(role = TrackRole.Melodic(lickDegreeOffset = 2)) },
        )
        val h = MixHistory().edit(whole)
        assertEquals(listOf("tracks[0].role"), h.vibeEdits.map { it.path })
        val flipped = h.edit(leaf)
        assertEquals(listOf("tracks[0].role.lickDegreeOffset"), flipped.vibeEdits.map { it.path })
    }

    @Test
    fun mixerPathsIgnored() {
        val edited = file.copy(
            energy = 0.9f,
            tracks = file.tracks.toMutableList().also { it[1] = it[1].copy(pan = 0.5f) },
        )
        assertTrue(MixHistory().edit(edited).vibeEdits.isEmpty())
        assertEquals(listOf("bpm"), MixHistory().edit(edited.copy(bpm = 130f)).vibeEdits.map { it.path })
    }

    @Test
    fun effectiveAtPointReplaysRowsBeforeIt() {
        val h = MixHistory().edit(file.copy(bpm = 130f)).mark(1_000).recordVibe(EditOrigin.AGENT, file, file.copy(bpm = 130f, seed = 7), 2_000)
        val point = h.items.filterIsInstance<HistoryPoint>().single()
        assertEquals(130f, h.effectiveAt(file, point.id).bpm)
        assertEquals(0, h.effectiveAt(file, point.id).seed)
        assertEquals(7, h.effective(file).seed)
        assertEquals(file, h.effectiveAt(file, MixHistory.FILE))
    }

    @Test
    fun toggleVibeRow() {
        val h = MixHistory().edit(file.copy(bpm = 130f))
        val off = h.toggle(h.vibeEdits.single().id)
        assertFalse(off.vibeEdits.single().on)
        assertEquals(120f, off.effective(file).bpm)
        assertEquals(130f, off.toggle(off.vibeEdits.single().id).effective(file).bpm)
    }

    @Test
    fun offRowsAreNeverFolded() {
        val h = MixHistory().edit(file.copy(bpm = 130f))
        val off = h.toggle(h.vibeEdits.single().id).edit(file.copy(bpm = 140f))
        assertEquals(listOf(false, true), off.vibeEdits.map { it.on })
        assertEquals(num(130f), off.vibeEdits[0].after)
    }

    @Test
    fun keepOnlyDropsVibeRows() {
        val h = MixHistory().edit(file.copy(bpm = 130f))
        assertTrue(h.keepOnly(BenchLayers(), pinned = setOf(0)).vibeEdits.isEmpty())
    }

    @Test
    fun uncapturedCountIncludesVibeRows() {
        val h = MixHistory().rec(MixControl.Level(2), level2, null, -3f).edit(file.copy(bpm = 130f))
        assertEquals(2, h.uncapturedCount)
        assertEquals(1, h.uncaptured.size)
        assertEquals(0, h.capture(1_000, "c", null).uncapturedCount)
    }

    @Test
    fun serializesRoundTrip() {
        val h = MixHistory().edit(file.copy(bpm = 130f)).mark(1_000)
        val set = StoredChangeSet("File", "hash", h.items, h.nextId, h.pointCount)
        val json = Json { allowStructuredMapKeys = true; encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(StoredChangeSet.serializer(), set)
        assertEquals(set, json.decodeFromString(StoredChangeSet.serializer(), text))
    }
}
