package org.balch.orpheus.djapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.LocalPanelIdleFade
import org.balch.orpheus.ui.viz.PanelIdleFade
import org.balch.orpheus.ui.widgets.EnumDropdown
import org.balch.orpheus.ui.widgets.TvInlinePicker
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A dropdown's menu renders in its own window, so it would not fade with the panel it belongs to:
 * open one for three seconds and the panel used to go, leaving the menu hanging over nothing.
 *
 * The keep-awake lives in the shared menu rather than at each call site, so these two cover every
 * panel picker in the app by construction. See the report for the one dropdown that hand-rolls its
 * own Material menu and is therefore still uncovered.
 *
 * ./gradlew :apps:djapp:shared:jvmTest --tests '*DropdownKeepsPanelsAwakeTest*' --rerun
 */
class DropdownKeepsPanelsAwakeTest {

    @Test
    fun `an open panel dropdown holds the panels up until it closes`() {
        assertHoldsPanelsUp("EnumDropdown") {
            EnumDropdown(
                label = "VIBE",
                selectedDisplay = "Dog House",
                entries = listOf("Bell Tolls", "Dog House"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.cosmicPurple,
            )
        }
    }

    @Test
    fun `an open television picker holds the panels up until it closes`() {
        assertHoldsPanelsUp("TvInlinePicker") {
            TvInlinePicker(
                label = "Viz",
                selectedDisplay = "Aquarium",
                entries = listOf("Random", "Aquarium"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.neonCyan,
            )
        }
    }

    private fun assertHoldsPanelsUp(what: String, picker: @Composable () -> Unit) {
        val fade = PanelIdleFade { 0L }
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            OrpheusTheme {
                CompositionLocalProvider(LocalPanelIdleFade provides fade) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF14141F))) { picker() }
                }
            }
        }
        try {
            scene.render()
            assertEquals(0, fade.modalCount, "$what held the panels up before it was opened")

            // The chip is at the top left; anywhere inside it opens the menu.
            val chip = Offset(20f, 20f)
            scene.sendPointerEvent(PointerEventType.Press, chip)
            scene.sendPointerEvent(PointerEventType.Release, chip)
            scene.render()
            assertEquals(1, fade.modalCount, "$what's open menu did not hold the panels up")

            // A click away dismisses the popup, and the count has to come back down with it.
            scene.sendPointerEvent(PointerEventType.Press, Offset(380f, 380f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(380f, 380f))
            scene.render()
            assertEquals(0, fade.modalCount, "$what kept the panels up after its menu closed")
        } finally {
            scene.close()
        }
    }
}
