package org.balch.orpheus.features.cowbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bend changes at drag rate. Only the bell's draw phase may read it, so the controls column
 * (and every knob in it) must not recompose while the bell is bent.
 */
class CowbellPanelRecompositionTest {

    @Test
    fun bendStepsDoNotRecomposeTheControls() {
        val state = MutableStateFlow(CowbellUiState())
        val feature = object : CowbellFeature {
            override val stateFlow = state
            override val actions = CowbellPanelActions.EMPTY
        }
        var compositions = 0
        CowbellScene(320, 330) {
            CompositionLocalProvider(LocalControlsCompositionProbe provides { compositions++ }) {
                CowbellPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
            }
        }.use { s ->
            s.frame()
            val before = compositions
            repeat(10) { i ->
                state.value = state.value.copy(bend = (i + 1) / 10f)
                s.frame()
            }
            assertEquals(before, compositions, "the controls recomposed on a bend")

            // A knob change still reaches them.
            state.value = state.value.copy(tone = 0.1f)
            s.frame()
            assertEquals(before + 1, compositions)
        }
    }

    @Test
    fun `bell frames do not recompose the controls`() {
        val feature = object : CowbellFeature {
            override val stateFlow = MutableStateFlow(CowbellUiState())
            override val actions = CowbellPanelActions.EMPTY
        }
        var compositions = 0
        CowbellScene(320, 330) {
            CompositionLocalProvider(LocalControlsCompositionProbe provides { compositions++ }) {
                CowbellPanel(feature = feature, modifier = Modifier.fillMaxSize(), isExpanded = true)
            }
        }.use { s ->
            s.frame()
            val before = compositions
            repeat(30) { s.frame() }
            assertTrue(s.scene.hasInvalidations(), "the bell's frame loop is not running")
            assertEquals(before, compositions, "the controls recomposed on a bell frame")
        }
    }
}
