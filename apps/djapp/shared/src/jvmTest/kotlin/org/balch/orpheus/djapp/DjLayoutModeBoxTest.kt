package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Going fullscreen on desktop flips the layout mode, which drops the vibe info sheet, a dialog
 * layer. Dropped inside a layout pass, the scene still lays out the disposed layer and throws
 * "RootNodeOwner is already disposed"; the mode has to change in composition instead.
 */
class DjLayoutModeBoxTest {

    @Test
    fun `a dialog dropped by a layout mode change does not crash the scene`() {
        var lastMode: DjLayoutMode? = null
        val scene = ImageComposeScene(width = 360, height = 780, density = Density(1f)) {
            DjLayoutModeBox(Modifier.fillMaxSize(), tvModeAllowed = true) { mode ->
                lastMode = mode
                if (mode != DjLayoutMode.LargeScreen) {
                    Dialog(onDismissRequest = {}) { Box(Modifier.size(40.dp)) }
                }
            }
        }
        try {
            repeat(3) { scene.render() }
            assertEquals(DjLayoutMode.Portrait, lastMode, "sanity: a phone-sized window starts in portrait")

            scene.constraints = Constraints.fixed(1920, 1080)
            repeat(3) { scene.render() }

            assertEquals(DjLayoutMode.LargeScreen, lastMode, "a fullscreen-sized window must reach the TV layout")
        } finally {
            scene.close()
        }
    }
}
