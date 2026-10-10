package org.balch.orpheus.djapp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KrakenTopBarTest {
    private var presses = 0
    private var releases = 0
    private var cycles = 0

    private val pulsar: PulsarFeature = run {
        val base = PulsarViewModel.previewFeature()
        object : PulsarFeature by base {
            override val actions: PulsarPanelActions = base.actions.copy(
                onKrakenPress = { presses++ },
                onKrakenRelease = { releases++ },
                onKrakenCycleTarget = { cycles++ },
            )
        }
    }

    private fun scene(showKraken: Boolean) = ImageComposeScene(1280, 120, Density(1f)) {
        OrpheusTheme {
            CompositionLocalProvider(LocalShowKraken provides showKraken) {
                DjTvTopBar(
                    panels = listOf(VibeInfoTab),
                    isDocked = { false },
                    onToggle = {},
                    vizFeature = VizViewModel.previewFeature(),
                    pulsarFeature = pulsar,
                    timerFeature = TimerViewModel.previewFeature(),
                )
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }

    private fun SemanticsNode.isKrakenPad() =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Kraken") }

    @Test
    fun theTopBarHasNoKrakenWithoutTheFlag() {
        val scene = scene(showKraken = false)
        try {
            scene.render()
            val nodes = scene.nodes()
            assertTrue(nodes.none { it.isKrakenPad() }, "the Kraken pad shows with the flag off")
            assertTrue(nodes.none { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "IV" } })
        } finally {
            scene.close()
        }
    }

    @Test
    fun theTopBarCarriesAWorkingKrakenPad() {
        val scene = scene(showKraken = true)
        try {
            scene.render()
            val nodes = scene.nodes()
            val pad = nodes.first { it.isKrakenPad() }
            val at = pad.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, at); scene.render()
            scene.sendPointerEvent(PointerEventType.Release, at); scene.render()
            assertEquals(1 to 1, presses to releases)
            val label = nodes.first { n ->
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "IV" }
            }
            scene.sendPointerEvent(PointerEventType.Press, label.boundsInRoot.center); scene.render()
            scene.sendPointerEvent(PointerEventType.Release, label.boundsInRoot.center); scene.render()
            assertEquals(1, cycles)
        } finally {
            scene.close()
        }
    }
}
