package org.balch.orpheus.ui.panels.slots

import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.ui.FactoryPanelSets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelNavCatalogTest {

    private fun entry(id: PanelId, shortLabel: String) =
        PanelNavEntry(id = id, shortLabel = shortLabel, group = PanelGroup.Fx)

    private val verb = SearchCandidate(entry(PanelId.REVERB, "VERB"), "Reverb", "Room and hall tails")
    private val delay = SearchCandidate(entry(PanelId.DELAY, "DELAY"), "Delay", "Tape-style echo repeats")
    private val lfo = SearchCandidate(entry(PanelId.LFO, "LFO"), "Low Frequency Osc", "Slow modulation")

    @Test
    fun catalogCoversDesktopSetExactly() {
        val ids = PanelNavEntries.map { it.id }
        assertEquals(FactoryPanelSets.DesktopScreen.visibleIds.toSet(), ids.toSet())
        assertEquals(ids.size, ids.toSet().size, "catalog has duplicate ids")
    }

    @Test
    fun wideIsPulsarAndBeats() {
        assertEquals(
            setOf(PanelId.PULSAR, PanelId.BEATS),
            PanelNavEntries.filter { it.wide }.map { it.id }.toSet(),
        )
        assertTrue(isWidePanel(PanelId.PULSAR))
        assertTrue(!isWidePanel(PanelId.REVERB))
    }

    @Test
    fun searchPrefersShortLabelPrefix() {
        val candidates = listOf(verb, delay)
        assertEquals(PanelId.DELAY, searchPanels("de", candidates).first().id)
        assertEquals(listOf(verb.entry), searchPanels("rev", candidates))
    }

    @Test
    fun searchMatchesDescriptionLast() {
        val candidates = listOf(verb, delay, lfo)
        val result = searchPanels("echo", candidates)
        assertEquals(listOf(delay.entry), result)

        val withPrefixHit = listOf(delay, SearchCandidate(entry(PanelId.HORN, "ECHOX"), "Horn", ""))
        assertEquals(
            listOf(PanelId.HORN, PanelId.DELAY),
            searchPanels("echo", withPrefixHit).map { it.id },
        )
    }

    @Test
    fun blankQueryReturnsAllInOrder() {
        val candidates = listOf(verb, delay, lfo)
        assertEquals(candidates.map { it.entry }, searchPanels("", candidates))
        assertEquals(candidates.map { it.entry }, searchPanels("   ", candidates))
    }

    @Test
    fun labelPrefixBeatsContains() {
        val candidates = listOf(lfo, SearchCandidate(entry(PanelId.EVO, "EVO"), "Evolution", ""))
        // "Low" hits LFO via its label prefix; the padded query is trimmed.
        assertEquals(listOf(PanelId.LFO), searchPanels("Low", candidates).map { it.id })
        assertEquals(listOf(PanelId.EVO), searchPanels("  EVO ", candidates).map { it.id })
    }
}
