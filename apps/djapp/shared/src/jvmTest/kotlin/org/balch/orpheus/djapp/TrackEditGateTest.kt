package org.balch.orpheus.djapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanel
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarSelectors
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DJ app hides the Pulsar grid's track taps and the detail strip unless `-PtrackEdit=true`. */
class TrackEditGateTest {

    // Holds its own selection the way PulsarViewModel does, so a tap that gets through shows up.
    private class Holder(initial: Int? = null) {
        private val base = PulsarViewModel.previewFeature()
        val state = MutableStateFlow(PulsarUiState(vibe = base.vibeList.first(), selectedTrack = initial))
        val feature = object : PulsarFeature {
            override val vibeList = base.vibeList
            override val vibeFlow = base.vibeFlow
            override fun applyVibe(vibe: Vibe) {}
            override val arrangementStateFlow = base.arrangementStateFlow
            override val stateFlow: StateFlow<PulsarUiState> = state
            override val actions = PulsarPanelActions.EMPTY.copy(
                selectTrack = { state.value = state.value.copy(selectedTrack = it) },
            )
        }
    }

    private fun scene(holder: Holder, trackEditing: () -> Boolean) = ImageComposeScene(400, 600, Density(1f)) {
        OrpheusTheme {
            CompositionLocalProvider(LocalShowTrackEdit provides trackEditing()) {
                PulsarPanel(
                    pulsar = holder.feature,
                    isExpanded = true,
                    showCollapsedHeader = false,
                    showExpandedTitle = false,
                    fillHeight = false,
                    selectors = PulsarSelectors.None,
                    trackEditing = LocalShowTrackEdit.current,
                )
            }
        }
    }

    private fun ImageComposeScene.texts(): List<String> {
        fun walk(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    // The detail strip's HI/LO engine labels appear only while it is shown.
    private fun ImageComposeScene.stripShown(): Boolean = texts().let { "HI" in it && "LO" in it }

    // A point inside the grid (it starts near the panel's top edge, mid-width).
    private fun ImageComposeScene.tapGrid() {
        val at = Offset(200f, 40f)
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
        render()
    }

    @Test
    fun gridTapSelectsWithTrackEdit() {
        val holder = Holder()
        val scene = scene(holder) { true }
        try {
            scene.render()
            assertTrue(!scene.stripShown())
            scene.tapGrid()
            assertNotNull(holder.state.value.selectedTrack, "the tap missed the grid")
            scene.render()
            assertTrue(scene.stripShown(), "the strip does not show after a tap with track editing on")
        } finally {
            scene.close()
        }
    }

    @Test
    fun gridTapIgnoredWithoutTrackEdit() {
        val holder = Holder()
        var editing by mutableStateOf(false)
        val scene = scene(holder) { editing }
        try {
            scene.render()
            scene.tapGrid()
            assertNull(holder.state.value.selectedTrack, "a tap selected a track with editing off")
            assertTrue(!scene.stripShown())

            editing = true
            scene.render()
            assertTrue(!scene.stripShown(), "turning editing on revealed a strip nobody tapped")
        } finally {
            scene.close()
        }
    }

    @Test
    fun aHeldSelectionStaysHiddenWithoutTrackEdit() {
        val holder = Holder(initial = 2)
        val scene = scene(holder) { false }
        try {
            scene.render()
            scene.render()
            assertTrue(!scene.stripShown(), "the strip shows for a selection the VM held with editing off")
        } finally {
            scene.close()
        }
    }
}
