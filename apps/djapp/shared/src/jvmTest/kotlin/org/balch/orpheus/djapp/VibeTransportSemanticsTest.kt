package org.balch.orpheus.djapp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ring's activatable node must itself carry the play/pause description and both skip
 * actions, not a non-clickable ancestor, so a screen reader's node for the ring announces state
 * rather than just the name/peek text. Walks [ImageComposeScene]'s own merged semantics tree
 * (no `compose.ui.test` dependency needed, unlike [org.balch.orpheus.ui.widgets.RotaryKnobLongPressTest]).
 */
class VibeTransportSemanticsTest {
    @Test
    fun theClickableNodeAnnouncesPlayStateAndCarriesBothSkipActions() {
        val scene = ImageComposeScene(200, 100, Density(1f)) {
            OrpheusTheme {
                VibeTransportItem(
                    name = "Space & Drift",
                    previousName = "Dog House",
                    nextName = "Stay Asleep",
                    progress = 0.5f,
                    paused = false,
                    onTogglePlayback = {},
                    onNext = {},
                    onPrevious = {},
                )
            }
        }
        try {
            scene.render()
            val root = scene.semanticsOwners.first().rootSemanticsNode
            val clickable = root.findFirst { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertNotNull(clickable, "no clickable node found in the semantics tree")

            val description = clickable.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            assertNotNull(description, "clickable node has no contentDescription")
            assertTrue(description.contains("Pause"), "expected \"Pause\" in \"$description\"")

            val actionLabels = clickable.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }?.toSet()
            assertEquals(setOf("Next vibe", "Previous vibe"), actionLabels)
        } finally {
            scene.close()
        }
    }

    // Past the restart threshold the dock tile reads "Restart"; the dome's left-drag action must agree.
    @Test
    fun aRestartingPreviousSaysRestart() {
        val scene = ImageComposeScene(200, 100, Density(1f)) {
            OrpheusTheme {
                VibeTransportItem(
                    name = "Space & Drift",
                    previousName = "Space & Drift",
                    nextName = "Stay Asleep",
                    progress = 0.5f,
                    paused = false,
                    onTogglePlayback = {},
                    onNext = {},
                    onPrevious = {},
                    previousRestarts = true,
                )
            }
        }
        try {
            scene.render()
            val root = scene.semanticsOwners.first().rootSemanticsNode
            val clickable = root.findFirst { it.config.getOrNull(SemanticsActions.CustomActions) != null }
            assertNotNull(clickable, "no node carries the skip actions")
            val actionLabels = clickable.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }?.toSet()
            assertEquals(setOf("Next vibe", "Restart vibe"), actionLabels)
        } finally {
            scene.close()
        }
    }
}

private fun SemanticsNode.findFirst(predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
    if (predicate(this)) return this
    children.forEach { child -> child.findFirst(predicate)?.let { return it } }
    return null
}
