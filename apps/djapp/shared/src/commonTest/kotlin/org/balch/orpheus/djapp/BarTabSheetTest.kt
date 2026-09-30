package org.balch.orpheus.djapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BarTabSheetTest {

    // The portrait playlist covers the stage: a panel's tab takes it back so the panel shows.
    @Test
    fun aPanelTabClosesThePlaylist() {
        listOf(DjTab, MixTab, HornTab, TimerTab).forEach { tab ->
            assertNull(sheetAfterBarTab(PlaylistRoute, tab), "$tab left the playlist open")
        }
    }

    @Test
    fun aPanelTabLeavesAnyOtherSheetAlone() {
        listOf(AiTab, VibeInfoTab).forEach { sheet ->
            assertEquals(sheet, sheetAfterBarTab(sheet, MixTab))
        }
        assertNull(sheetAfterBarTab(null, MixTab))
    }

    @Test
    fun aSheetsTabTogglesItAndReplacesAnyOther() {
        assertEquals(AiTab, sheetAfterBarTab(null, AiTab))
        assertNull(sheetAfterBarTab(AiTab, AiTab))
        assertEquals(AiTab, sheetAfterBarTab(PlaylistRoute, AiTab))
    }
}
