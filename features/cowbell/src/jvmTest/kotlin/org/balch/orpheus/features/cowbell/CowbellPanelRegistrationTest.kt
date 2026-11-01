package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.balch.orpheus.core.features.FeaturePanel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rim glow's poll follows the panel: on while it is composed, off when it leaves. */
class CowbellPanelRegistrationTest {

    @Test
    fun vizRequestFollowsThePanel() {
        val engine = RecordingSynthEngine()
        assertVizFollows(engine, CowbellPanelRegistration(CowbellViewModel.previewFeature(), engine))
    }

    @Test
    fun vizRequestFollowsTheBenderPanel() {
        val engine = RecordingSynthEngine()
        assertVizFollows(engine, CowbellBenderPanelRegistration(CowbellViewModel.previewFeature(), engine))
    }

    private fun assertVizFollows(engine: RecordingSynthEngine, registration: FeaturePanel) {
        var shown by mutableStateOf(true)
        CowbellScene(320, 330) {
            if (shown) registration.Content(Modifier.fillMaxSize(), isExpanded = true, onExpandedChange = {}, onDialogActiveChange = {})
        }.use { s ->
            assertEquals(listOf(true), engine.cowbellVizRequests)
            shown = false
            s.frame()
            assertEquals(listOf(true, false), engine.cowbellVizRequests)
        }
    }
}
