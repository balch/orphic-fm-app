package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.features.pulsar.mixer.MixerFeature
import org.balch.orpheus.features.pulsar.mixer.MixerPanelActions
import org.balch.orpheus.features.pulsar.mixer.MixerUiState
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import org.balch.orpheus.ui.theme.OrpheusTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MixTuningToggleTest {
    private val mutes = mutableListOf<Int>()
    private val solos = mutableListOf<Int>()

    private val mixer = object : MixerFeature {
        override val stateFlow: StateFlow<MixerUiState> = MutableStateFlow(
            MixerUiState(
                tuning = TrackTuning().toggleSolo(3).toggleSolo(4).toggleMute(4).withLevel(3, 1.3f),
            )
        )
        override val actions = MixerPanelActions(
            setGroupGain = { _, _ -> },
            setDrive = {},
            toggleTrackMute = { mutes += it },
            toggleTrackSolo = { solos += it },
        )
    }

    // A phone's cover screen: the strips must fit 360dp.
    // Null leaves the flag at its build default, so a default build proves TRACKS ships on.
    private fun scene(showTuning: Boolean?) = ImageComposeScene(360, 320, Density(1f)) {
        OrpheusTheme {
            val panel = @Composable {
                DjMixPanel(feature = mixer, trackVizFlows = List(8) { MutableStateFlow(FloatArray(0)) })
            }
            if (showTuning == null) panel()
            else CompositionLocalProvider(LocalShowTuning provides showTuning) { panel() }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun SemanticsNode.hasText(text: String) =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }

    private fun SemanticsNode.described(prefix: String) =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) }

    private fun ImageComposeScene.tap(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, at); render()
        sendPointerEvent(PointerEventType.Release, at); render()
    }

    @Test
    fun killSwitchHidesTracks() {
        val scene = scene(showTuning = false)
        try {
            scene.render()
            val nodes = scene.nodes()
            assertTrue(nodes.none { it.hasText("TRACKS") || it.hasText("BAND") }, "the toggle shows with the flag off")
            assertTrue(nodes.none { it.described("Strip ") })
            assertTrue(nodes.any { it.hasText("PERC") }, "the bus faders are missing")
        } finally {
            scene.close()
        }
    }

    @Test
    fun bandViewShownByDefaultWithTracksOneTapAway() {
        val scene = scene(showTuning = null)
        try {
            scene.render()
            File("build/mix-tuning").mkdirs()
            runCatching { File("build/mix-tuning/bus.png").writeBytes(scene.render().encodeToData()!!.bytes) }
            var nodes = scene.nodes()
            assertTrue(nodes.any { it.hasText("TRACKS") } && nodes.any { it.hasText("BAND") })
            assertTrue(nodes.any { it.hasText("PERC") }, "the Mix tab does not open on BAND")
            assertTrue(nodes.none { it.described("Strip ") }, "the Mix tab opens on TRACKS")

            scene.tap(nodes.first { it.hasText("TRACKS") })
            runCatching { File("build/mix-tuning/tracks.png").writeBytes(scene.render().encodeToData()!!.bytes) }
            nodes = scene.nodes()
            val muteButtons = nodes.filter { it.described("Mute ") }
            assertEquals(8, muteButtons.size)
            // Every strip sits inside the 360dp width.
            assertTrue(muteButtons.all { it.boundsInRoot.right <= 360f }, "a strip runs off a 360dp screen")
            scene.tap(nodes.first { it.described("Mute BASS") })
            scene.tap(nodes.first { it.described("Solo PAD") })
            assertEquals(listOf(3), mutes)
            assertEquals(listOf(5), solos)

            scene.tap(nodes.first { it.hasText("BAND") })
            nodes = scene.nodes()
            assertTrue(nodes.any { it.hasText("PERC") }, "BAND does not show the bus faders")
            assertTrue(nodes.none { it.described("Strip ") })
        } finally {
            scene.close()
        }
    }
}
