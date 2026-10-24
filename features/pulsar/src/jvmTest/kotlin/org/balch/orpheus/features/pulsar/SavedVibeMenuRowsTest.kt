package org.balch.orpheus.features.pulsar

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import org.balch.orpheus.features.pulsar.playback.SavedVibe
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The VIBE menu's saved rows, in a plain list: the menu's popup only hosts them. */
class SavedVibeMenuRowsTest {
    private val now = System.currentTimeMillis()
    private val ghost = SavedVibe("Ghost Signal", now, mkMinimalVibe("Ghost Signal"))

    private class Menu(saved: List<SavedVibe>, now: Long) {
        val picked = mutableListOf<SavedVibe>()
        val deleted = mutableListOf<String>()
        var dismissed = 0
        val scene = ImageComposeScene(300, 400, Density(1f)) {
            OrpheusTheme {
                LazyColumn {
                    savedVibeMenuRows(saved, "Dog House", now, OrpheusColors.cosmicPurple, { picked += it }, { deleted += it }) { dismissed++ }
                }
            }
        }.also { it.render() }

        private fun nodes(): List<SemanticsNode> {
            val out = mutableListOf<SemanticsNode>()
            fun walk(n: SemanticsNode) { out += n; n.children.forEach(::walk) }
            walk(scene.semanticsOwners.first().rootSemanticsNode)
            return out
        }

        fun find(label: String) = nodes().firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }

        fun click(label: String) {
            val node = nodes().firstOrNull { n ->
                n.config.getOrNull(SemanticsActions.OnClick) != null && (
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true)
            }
            assertNotNull(node, "nothing clickable labelled \"$label\"").config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
        }
    }

    @Test
    fun aTapPlaysAndClosesTheMenu() {
        val menu = Menu(listOf(ghost), now)
        try {
            assertNotNull(menu.find("AI VIBES · 1"))
            menu.click("Ghost Signal")
            assertEquals(listOf(ghost), menu.picked)
            assertEquals(1, menu.dismissed)
        } finally { menu.scene.close() }
    }

    @Test
    fun theTrashAsksInTheRowThenDeletes() {
        val menu = Menu(listOf(ghost), now)
        try {
            menu.click("Delete Ghost Signal")
            assertNotNull(menu.find("Delete Ghost Signal?"))
            menu.click("Keep")
            assertNull(menu.find("Delete Ghost Signal?"))
            menu.click("Delete Ghost Signal")
            menu.click("Delete")
            assertEquals(listOf("Ghost Signal"), menu.deleted)
            assertEquals(emptyList(), menu.picked)
        } finally { menu.scene.close() }
    }

    @Test
    fun nothingWhenNoneAreSaved() {
        val menu = Menu(emptyList(), now)
        try { assertNull(menu.find("AI VIBES · 0")) } finally { menu.scene.close() }
    }
}
