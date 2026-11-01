package org.balch.orpheus.djapp.ai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.djapp.ai.licklab.ExportedLick
import org.balch.orpheus.djapp.ai.licklab.LickLabActions
import org.balch.orpheus.djapp.ai.licklab.LickLabFeature
import org.balch.orpheus.djapp.ai.licklab.LickLabUiState
import org.balch.orpheus.djapp.ai.licklab.LickSlot
import org.balch.orpheus.djapp.ai.licklab.SlotRef
import org.balch.orpheus.djapp.ai.licklab.Take
import org.balch.orpheus.djapp.ai.licklab.TakeContext
import org.balch.orpheus.djapp.ai.licklab.TakeExport
import org.balch.orpheus.djapp.ai.licklab.TakeNote
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.Rest

class KeyboardReadToolTest {
    private val pulsar = FakePulsar(listOf(vibe("Night Drive")))
    private val slot = SlotRef(LickSlot.Lead)

    private fun take(id: Long, starred: Boolean, midi: Int = 69, at: SlotRef = slot, followsChords: Boolean = false) =
        Take(id, listOf(TakeNote(0.0, 1.0, midi, 0.8f, false, -1f)), 0, 4, starred, TakeContext(vibe("Night Drive"), at, followsChords))

    private val lick = ExportedLick(
        steps = listOf(LickStep(2, 1f, 0.8f), Rest(1f)),
        loopLength = 4,
        chromaticNotes = listOf(0),
        chromaticMidi = listOf(70),
        mergedCount = 1,
        clamped = mapOf(0 to 70),
    )

    private inner class FakeLab(takes: List<Take>) : LickLabFeature {
        override val stateFlow = MutableStateFlow(
            LickLabUiState(armedTrack = 3, slot = slot, keyLabel = "A minor · 0 = A4", takes = takes),
        )
        override val actions: LickLabActions = LickLabActions.EMPTY
        override fun exportFor(takeId: Long): ExportedLick? = lick.takeIf { stateFlow.value.takes.any { it.id == takeId } }
        override fun kotlinFor(takeId: Long): String? = exportFor(takeId)?.let { TakeExport.toKotlin(it, slot) }
        override val synthControl: SynthFeature.SynthControl get() = SynthFeature.SynthControl.Empty
    }

    private fun read(lab: LickLabFeature, args: KeyboardReadArgs = KeyboardReadArgs()) =
        runBlocking { KeyboardReadTool({ lab }, { pulsar }).tool.execute(args) }

    @Test
    fun starredOnlyReturnsTheStarredTakeWithItsExportedKotlin() {
        val result = read(FakeLab(listOf(take(1, false), take(2, true))), KeyboardReadArgs(starredOnly = true))

        assertTrue(result.ok, result.error)
        val t = result.takes.single()
        assertEquals(2L, t.id)
        assertTrue(t.starred)
        assertEquals(TakeExport.toKotlin(lick, slot), t.kotlin)
    }

    @Test
    fun carriesContextAndEveryMarkTheUiShows() {
        pulsar.stateFlow.value = pulsar.stateFlow.value.copy(bpm = 96f)
        val result = read(FakeLab(listOf(take(1, false))))

        assertEquals("Night Drive", result.vibe)
        assertEquals(3, result.track)
        assertEquals("Lead", result.slot)
        assertEquals("A minor · 0 = A4", result.key)
        assertEquals(96f, result.bpm)
        val t = result.takes.single()
        assertEquals(listOf(true, false), t.steps.map { !it.rest })
        assertEquals(2, t.steps[0].degree)
        assertEquals(4, t.loopLength)
        assertEquals(listOf(0), t.chromaticSteps)
        assertEquals(listOf(70), t.chromaticMidi)
        assertEquals(1, t.mergedCount)
        assertEquals(mapOf(0 to 70), t.clamped)
        assertFalse(t.overCap)
        assertEquals("Lead", t.slot)
        assertFalse(t.followsChords)
    }

    @Test
    fun eachTakeReportsItsOwnSlotAndChordFollowing() {
        val result = read(FakeLab(listOf(take(1, false, at = SlotRef(LickSlot.Pool, 2), followsChords = true), take(2, false))))

        assertEquals("Lead", result.slot)
        assertEquals(listOf("Pool 2", "Lead"), result.takes.map { it.slot })
        assertEquals(listOf(true, false), result.takes.map { it.followsChords })
    }

    @Test
    fun lastKeepsTheNewestTakes() {
        val result = read(FakeLab((1L..6L).map { take(it, false) }), KeyboardReadArgs(last = 2))

        assertEquals(listOf(5L, 6L), result.takes.map { it.id })
    }

    @Test
    fun toolIsNamedForMcp() {
        assertEquals("keyboard_read", KeyboardReadTool({ error("unused") }, { error("unused") }).tool.descriptor.name)
    }
}
