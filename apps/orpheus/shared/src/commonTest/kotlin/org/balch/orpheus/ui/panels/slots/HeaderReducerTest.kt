package org.balch.orpheus.ui.panels.slots

import kotlinx.serialization.json.Json
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.panels.factoryPanelSet
import org.balch.orpheus.core.preferences.AppPreferences
import org.balch.orpheus.core.preferences.PanelSlotsPrefs
import org.balch.orpheus.ui.FactoryPanelSets
import org.balch.orpheus.ui.panels.HeaderPanelUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderReducerTest {
    private val registered: Set<PanelId> = PanelNavEntries.map { it.id }.toSet()
    private val defaultSet = FactoryPanelSets.DesktopScreen

    private fun state(capacity: Int = 0) = HeaderPanelUiState(slots = PanelSlots(capacity = capacity))

    private fun restore(prefs: AppPreferences, set: org.balch.orpheus.core.panels.PanelSet = defaultSet) =
        restoreSlots(prefs, set, registered)

    @Test
    fun busExpandOpensAndCollapseCloses() {
        val opened = reduceHeader(state(capacity = 4), HeaderIntent.Open(PanelId.REVERB))
        assertEquals(listOf(PanelId.REVERB), opened.slots.openIds)
        val closed = reduceHeader(opened, HeaderIntent.Close(PanelId.REVERB))
        assertTrue(closed.slots.openIds.isEmpty())
        assertEquals(listOf(PanelId.REVERB), closed.slots.recents)
    }

    @Test
    fun applySetOpensExpandedIdsInSetOrder() {
        val next = reduceHeader(state(capacity = 2), HeaderIntent.ApplySet(FactoryPanelSets.DesktopScreen))
        assertEquals(listOf(PanelId.DISTORTION, PanelId.PULSAR), next.slots.openIds)
        assertEquals(FactoryPanelSets.DesktopScreen, next.activePanelSet)
    }

    @Test
    fun restorePrefersPanelSlotsPrefs() {
        val prefs = AppPreferences(
            lastExpandedPanelsJson = """{"reverb":true}""",
            panelSlots = PanelSlotsPrefs(
                open = listOf("delay", "lfo"),
                lastUsed = listOf("lfo", "delay"),
                pinned = listOf("lfo"),
                recents = listOf("horn"),
                railVisible = true,
                recentsStripVisible = false,
            ),
        )
        val r = restore(prefs)
        assertEquals(listOf(PanelId.DELAY, PanelId.LFO), r.slots.openIds)
        assertEquals(listOf(PanelId.LFO, PanelId.DELAY), r.slots.lastUsed)
        assertEquals(setOf(PanelId.LFO), r.slots.pinned)
        assertEquals(listOf(PanelId.HORN), r.slots.recents)
        assertEquals(0, r.slots.capacity)
        assertEquals(true, r.railVisible)
        assertFalse(r.recentsStripVisible)
    }

    @Test
    fun restoreMigratesLastExpandedPanelsJson() {
        val prefs = AppPreferences(lastExpandedPanelsJson = """{"reverb":true,"lfo":true,"delay":false}""")
        val r = restore(prefs)
        assertEquals(listOf(PanelId.LFO, PanelId.REVERB), r.slots.openIds)
        assertNull(r.railVisible)
        assertTrue(r.recentsStripVisible)
    }

    @Test
    fun restoreFallsBackToDefaultSet() {
        val set = factoryPanelSet("Test") {
            expand(PanelId.REVERB)
            collapse(PanelId.DELAY)
            expand(PanelId.LFO)
        }
        val r = restore(AppPreferences(), set)
        assertEquals(listOf(PanelId.REVERB, PanelId.LFO), r.slots.openIds)
    }

    @Test
    fun restoreFallsBackWhenJsonIsMalformed() {
        val r = restore(AppPreferences(lastExpandedPanelsJson = "not json"))
        assertEquals(listOf(PanelId.DISTORTION, PanelId.PULSAR), r.slots.openIds)
    }

    @Test
    fun restoreDropsUnregisteredIds() {
        val prefs = AppPreferences(
            panelSlots = PanelSlotsPrefs(
                open = listOf("record", "gone"),
                lastUsed = listOf("gone", "record"),
                pinned = listOf("record", "gone"),
                recents = listOf("gone", "record"),
            ),
        )
        val r = restoreSlots(prefs, defaultSet, registered = setOf(PanelId.LFO))
        assertTrue(r.slots.openIds.isEmpty())
        assertTrue(r.slots.pinned.isEmpty())
        assertTrue(r.slots.recents.isEmpty())
        assertTrue(r.slots.lastUsed.isEmpty())
    }

    @Test
    fun prefsRoundTrip() {
        val s = listOf(PanelId.LFO, PanelId.DELAY, PanelId.REVERB, PanelId.HORN)
            .fold(PanelSlots(capacity = 4)) { acc, id -> acc.open(id, ::isWidePanel) }
            .togglePin(PanelId.DELAY)
            .close(PanelId.HORN)
            .touch(PanelId.REVERB)
        val prefs = AppPreferences(panelSlots = s.toPrefs(railVisible = false, recentsStripVisible = false))
        val decoded = Json.decodeFromString<AppPreferences>(Json.encodeToString(prefs))
        val r = restore(decoded)
        assertEquals(s.openIds, r.slots.openIds)
        assertEquals(s.pinned, r.slots.pinned)
        assertEquals(s.recents, r.slots.recents)
        assertEquals(s.lastUsed, r.slots.lastUsed)
        assertEquals(false, r.railVisible)
        assertFalse(r.recentsStripVisible)
    }

    @Test
    fun restoreIntentReplacesSlotsAndKeepsKnownCapacity() {
        val before = reduceHeader(state(), HeaderIntent.SetCapacity(2))
        val r = restore(
            AppPreferences(panelSlots = PanelSlotsPrefs(open = listOf("lfo", "delay", "reverb"))),
        )
        val after = reduceHeader(before, r)
        assertEquals(2, after.slots.capacity)
        assertEquals(2, after.slots.openIds.size)
    }

    @Test
    fun restoreMarksStateRestored() {
        assertFalse(state().restored)
        val after = reduceHeader(state(), restore(AppPreferences()))
        assertTrue(after.restored)
        assertTrue(reduceHeader(after, HeaderIntent.SetCapacity(3)).restored)
    }

    @Test
    fun railAndStripIntentsUpdateState() {
        val s = reduceHeader(state(), HeaderIntent.SetRailVisible(true))
        assertEquals(true, s.railVisible)
        assertFalse(reduceHeader(s, HeaderIntent.SetRecentsStripVisible(false)).recentsStripVisible)
    }

    @Test
    fun isExpandedFollowsSlots() {
        val s = reduceHeader(state(), HeaderIntent.Open(PanelId.LFO))
        assertTrue(s.isExpanded(PanelId.LFO))
        assertFalse(s.isExpanded(PanelId.DELAY))
        assertFalse(reduceHeader(s, HeaderIntent.Close(PanelId.LFO)).isExpanded(PanelId.LFO))
    }
}
