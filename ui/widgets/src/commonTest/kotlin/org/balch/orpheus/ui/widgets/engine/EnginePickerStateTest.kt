package org.balch.orpheus.ui.widgets.engine

import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnginePickerStateTest {

    private val duo = EngineCatalog.choices(EngineSlotKind.DUO, hasNative = true)
    private val drum = EngineCatalog.choices(EngineSlotKind.DRUM, hasNative = true)
    private val pulsar = EngineCatalog.choices(EngineSlotKind.PULSAR, hasNative = true)

    private fun patch(engine: OrpheusEngineId, index: Int) =
        EngineChoice.Patch(EngineCatalog.patches.first { it.engine == engine && it.index == index })

    @Test
    fun opensOnCurrentCategory() {
        val state = EnginePickerState(duo, EngineChoice.Engine(OrpheusEngineId.VA), startCategory = null)
        assertEquals(EngineCategory.ANALOG, state.openCategory())
        assertEquals(EngineCategory.ANALOG, state.category)
    }

    @Test
    fun drumStartCategoryWins() {
        val state = EnginePickerState(drum, EngineChoice.Engine(OrpheusEngineId.VA), EngineCategory.DRUMS)
        assertEquals(EngineCategory.DRUMS, state.openCategory())
        assertEquals(EngineCategory.DRUMS, state.category)
    }

    @Test
    fun hiddenCurrentOpensAll() {
        val state = EnginePickerState(drum, EngineChoice.Engine(OrpheusEngineId.FM_DRUM), startCategory = null)
        assertNull(state.openCategory())
        assertNull(state.category)
    }

    @Test
    fun queryOverridesChip() {
        val state = EnginePickerState(pulsar, EngineChoice.Engine(OrpheusEngineId.VA), startCategory = null)
        state.category = EngineCategory.ANALOG
        state.query = "piano"
        val shown = state.visible.flatMap { it.second }
        assertTrue(patch(OrpheusEngineId.DX2, 0) in shown, "E piano 1 is found from the Analog chip")
        assertTrue(shown.none { it == EngineChoice.Engine(OrpheusEngineId.VA) }, "the chip no longer filters")
        assertTrue(shown.filter { it.engine == OrpheusEngineId.DX2 }.isNotEmpty())
    }

    @Test
    fun patchesGroupByFamily() {
        val state = EnginePickerState(pulsar, patch(OrpheusEngineId.DX2, 0), startCategory = null)
        assertEquals(EngineCategory.DX_PATCHES, state.category)
        val headers = state.visible.map { it.first }
        assertEquals("Bass · DX", headers.first())
        // Patch 19 is Bass too, so it joins the first section instead of starting a new one.
        assertEquals(headers.distinct(), headers)
        assertTrue(patch(OrpheusEngineId.DX, 19) in state.visible.first().second)
        assertTrue("Keys · DX2" in headers)
    }

    @Test
    fun recentShowsOnlyChoicesThisSlotOffers() {
        val state = EnginePickerState(duo, EngineChoice.Engine(OrpheusEngineId.VA), startCategory = null)
        // MST is Pulsar-only and a bare DX2 is never a tile: neither belongs in a Duo's Recent.
        state.recentKeys = listOf("MST", "DX2", "DX2:0", "VA", "nonsense")
        state.selectRecent()
        assertEquals(
            listOf(EnginePickerState.RECENT_HEADER to listOf(patch(OrpheusEngineId.DX2, 0), EngineChoice.Engine(OrpheusEngineId.VA))),
            state.visible,
        )
    }

    @Test
    fun picksFirstVisibleOrNothing() {
        val state = EnginePickerState(pulsar, EngineChoice.Engine(OrpheusEngineId.VA), startCategory = null)
        state.query = "zzz"
        assertNull(state.firstVisible())
        state.query = "piano"
        assertEquals(patch(OrpheusEngineId.DX2, 0), state.firstVisible())
    }
}
